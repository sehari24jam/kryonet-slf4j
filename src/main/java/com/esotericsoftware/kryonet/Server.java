/* Copyright (c) 2008, Nathan Sweet
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following
 * conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided with the distribution.
 * - Neither the name of Esoteric Software nor the names of its contributors may be used to endorse or promote products derived
 * from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. */

package com.esotericsoftware.kryonet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.IntMap;
import com.esotericsoftware.kryonet.FrameworkMessage.DiscoverHost;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterTCP;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterUDP;

/** Manages TCP and optionally UDP connections from many {@link Client Clients}.
 * @author Nathan Sweet <misc@n4te.com> */
public class Server implements EndPoint {
	private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);
	
	private final Serialization serialization;
	private final int writeBufferSize, objectBufferSize;
	private final Selector selector;
	private int emptySelects;
	private ServerSocketChannel serverChannel;
	private UdpConnection udp;
	private Connection[] connections = {};
	private IntMap<Connection> pendingConnections = new IntMap<Connection>();
	Listener[] listeners = {};
	private Object listenerLock = new Object();
	private int nextConnectionID = 1;
	private volatile boolean shutdown;
	private Object updateLock = new Object();
	private Thread updateThread;
	private ServerDiscoveryHandler discoveryHandler;

	private Listener dispatchListener = new Listener() {
		public void connected (Connection connection) {
			Listener[] listeners = Server.this.listeners;
			for (int i = 0, n = listeners.length; i < n; i++)
				listeners[i].connected(connection);
		}

		public void disconnected (Connection connection) {
			removeConnection(connection);
			Listener[] listeners = Server.this.listeners;
			for (int i = 0, n = listeners.length; i < n; i++)
				listeners[i].disconnected(connection);
		}

		public void received (Connection connection, Object object) {
			Listener[] listeners = Server.this.listeners;
			for (int i = 0, n = listeners.length; i < n; i++)
				listeners[i].received(connection, object);
		}

		public void idle (Connection connection) {
			Listener[] listeners = Server.this.listeners;
			for (int i = 0, n = listeners.length; i < n; i++)
				listeners[i].idle(connection);
		}
	};

	/** Creates a Server with a write buffer size of 16384 and an object buffer size of 2048. */
	public Server () {
		this(16384, 2048);
	}

	/** @param writeBufferSize One buffer of this size is allocated for each connected client. Objects are serialized to the write
	 *           buffer where the bytes are queued until they can be written to the TCP socket.
	 *           <p>
	 *           Normally the socket is writable and the bytes are written immediately. If the socket cannot be written to and
	 *           enough serialized objects are queued to overflow the buffer, then the connection will be closed.
	 *           <p>
	 *           The write buffer should be sized at least as large as the largest object that will be sent, plus some head room to
	 *           allow for some serialized objects to be queued in case the buffer is temporarily not writable. The amount of head
	 *           room needed is dependent upon the size of objects being sent and how often they are sent.
	 * @param objectBufferSize One (using only TCP) or three (using both TCP and UDP) buffers of this size are allocated. These
	 *           buffers are used to hold the bytes for a single object graph until it can be sent over the network or
	 *           deserialized.
	 *           <p>
	 *           The object buffers should be sized at least as large as the largest object that will be sent or received. */
	public Server (int writeBufferSize, int objectBufferSize) {
		this(writeBufferSize, objectBufferSize, new KryoSerialization());
	}

	public Server (int writeBufferSize, int objectBufferSize, Serialization serialization) {
		this.writeBufferSize = writeBufferSize;
		this.objectBufferSize = objectBufferSize;

		this.serialization = serialization;

		this.discoveryHandler = ServerDiscoveryHandler.DEFAULT;

		try {
			selector = Selector.open();
		} catch (IOException ex) {
			throw new RuntimeException("Error opening selector.", ex);
		}
	}

	public void setDiscoveryHandler (ServerDiscoveryHandler newDiscoveryHandler) {
		discoveryHandler = newDiscoveryHandler;
	}

	public Serialization getSerialization () {
		return serialization;
	}

	public Kryo getKryo () {
		return ((KryoSerialization)serialization).getKryo();
	}

	/** Opens a TCP only server.
	 * @throws IOException if the server could not be opened. */
	public void bind (int tcpPort) throws IOException {
		bind(new InetSocketAddress(tcpPort), null);
	}

	/** Opens a TCP and UDP server.
	 * @throws IOException if the server could not be opened. */
	public void bind (int tcpPort, int udpPort) throws IOException {
		bind(new InetSocketAddress(tcpPort), new InetSocketAddress(udpPort));
	}

	/** @param udpPort May be null. */
	public void bind (InetSocketAddress tcpPort, InetSocketAddress udpPort) throws IOException {
		final String methodName = "bind : ";
		
		close();
		synchronized (updateLock) {
			selector.wakeup();
			try {
				serverChannel = selector.provider().openServerSocketChannel();
				serverChannel.socket().bind(tcpPort);
				serverChannel.configureBlocking(false);
				serverChannel.register(selector, SelectionKey.OP_ACCEPT);
				LOGGER.debug("{} Accepting connections on port: {}/TCP", methodName, tcpPort);

				if (udpPort != null) {
					udp = new UdpConnection(serialization, objectBufferSize);
					udp.bind(selector, udpPort);
					LOGGER.debug("{} Accepting connections on port: {}/UDP", methodName, udpPort);
				}
			} catch (IOException ex) {
				close();
				throw ex;
			}
		}
		LOGGER.info("{} Server opened.", methodName);
	}

	/** Accepts any new connections and reads or writes any pending data for the current connections.
	 * @param timeout Wait for up to the specified milliseconds for a connection to be ready to process. May be zero to return
	 *           immediately if there are no connections to process. */
	public void update (int timeout) throws IOException {
		final String methodName = "update : ";
		
		updateThread = Thread.currentThread();
		synchronized (updateLock) { // Blocks to avoid a select while the selector is used to bind the server connection.
		}
		long startTime = System.currentTimeMillis();
		int select = 0;
		if (timeout > 0) {
			select = selector.select(timeout);
		} else {
			select = selector.selectNow();
		}
		if (select == 0) {
			emptySelects++;
			if (emptySelects == 100) {
				emptySelects = 0;
				// NIO freaks and returns immediately with 0 sometimes, so try to keep from hogging the CPU.
				long elapsedTime = System.currentTimeMillis() - startTime;
				try {
					if (elapsedTime < 25) Thread.sleep(25 - elapsedTime);
				} catch (InterruptedException ex) {
				}
			}
		} else {
			emptySelects = 0;
			Set<SelectionKey> keys = selector.selectedKeys();
			synchronized (keys) {
				UdpConnection udp = this.udp;
				outer:
				for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext();) {
					keepAlive();
					SelectionKey selectionKey = iter.next();
					iter.remove();
					Connection fromConnection = (Connection)selectionKey.attachment();
					try {
						int ops = selectionKey.readyOps();

						if (fromConnection != null) { // Must be a TCP read or write operation.
							if (udp != null && fromConnection.udpRemoteAddress == null) {
								fromConnection.close();
								continue;
							}
							if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
								try {
									while (true) {
										Object object = fromConnection.tcp.readObject(fromConnection);
										if (object == null) break;
										if (LOGGER.isDebugEnabled()) {
											String objectString = object == null ? "null" : object.getClass().getSimpleName();
											if (!(object instanceof FrameworkMessage)) {
												LOGGER.debug("{}{} received TCP: {}", methodName, fromConnection, objectString);
											} else if (LOGGER.isTraceEnabled()) {
												LOGGER.trace("{}{} received TCP: {}", methodName, fromConnection, objectString);
											}
										}
										fromConnection.notifyReceived(object);
									}
								} catch (IOException ex) {
									if (LOGGER.isTraceEnabled()) {
										LOGGER.trace("Unable to read TCP from: " + fromConnection, ex);
									} else if (LOGGER.isDebugEnabled()) {
										LOGGER.debug("{}{} update: {}", methodName, fromConnection, ex.getMessage());
									}
									fromConnection.close();
								} catch (KryoNetException ex) {
									LOGGER.error("Error reading TCP from connection: " + fromConnection, ex);
									fromConnection.close();
								}
							}
							if ((ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
								try {
									fromConnection.tcp.writeOperation();
								} catch (IOException ex) {
									if (LOGGER.isTraceEnabled()) {
										LOGGER.trace("Unable to write TCP to connection: " + fromConnection, ex);
									} else if (LOGGER.isDebugEnabled()) {
										LOGGER.debug("{}{} update: {}", methodName, fromConnection, ex.getMessage());
									}
									fromConnection.close();
								}
							}
							continue;
						}

						if ((ops & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT) {
							ServerSocketChannel serverChannel = this.serverChannel;
							if (serverChannel == null) continue;
							try {
								SocketChannel socketChannel = serverChannel.accept();
								if (socketChannel != null) acceptOperation(socketChannel);
							} catch (IOException ex) {
								LOGGER.debug("Unable to accept new connection.", ex);
							}
							continue;
						}

						// Must be a UDP read operation.
						if (udp == null) {
							selectionKey.channel().close();
							continue;
						}
						InetSocketAddress fromAddress;
						try {
							fromAddress = udp.readFromAddress();
						} catch (IOException ex) {
							LOGGER.warn("Error reading UDP data.", ex);
							continue;
						}
						if (fromAddress == null) continue;

						Connection[] connections = this.connections;
						for (int i = 0, n = connections.length; i < n; i++) {
							Connection connection = connections[i];
							if (fromAddress.equals(connection.udpRemoteAddress)) {
								fromConnection = connection;
								break;
							}
						}

						Object object;
						try {
							object = udp.readObject(fromConnection);
						} catch (KryoNetException ex) {
							if (fromConnection != null) {
								LOGGER.error("Error reading UDP from connection: " + fromConnection, ex);
							} else
								LOGGER.warn("Error reading UDP from unregistered address: " + fromAddress, ex);
							continue;
						}

						if (object instanceof FrameworkMessage) {
							if (object instanceof RegisterUDP) {
								// Store the fromAddress on the connection and reply over TCP with a RegisterUDP to indicate success.
								int fromConnectionID = ((RegisterUDP)object).connectionID;
								Connection connection = pendingConnections.remove(fromConnectionID);
								if (connection != null) {
									if (connection.udpRemoteAddress != null) continue outer;
									connection.udpRemoteAddress = fromAddress;
									addConnection(connection);
									connection.sendTCP(new RegisterUDP());
									LOGGER.debug("{} Port {}/UDP connected to: {}", methodName, udp.datagramChannel.socket().getLocalPort(),
											fromAddress);
									connection.notifyConnected();
									continue;
								}
								LOGGER.debug("{} Ignoring incoming RegisterUDP with invalid connection ID: {}", methodName, fromConnectionID);
								continue;
							}
							if (object instanceof DiscoverHost) {
								try {
									boolean responseSent = discoveryHandler.onDiscoverHost(udp, fromAddress, serialization);
									if (responseSent) {
										LOGGER.debug("{} Responded to host discovery from: {}", methodName, fromAddress);
									}
								} catch (IOException ex) {
									LOGGER.warn("Error replying to host discovery from: " + fromAddress, ex);
								}
								continue;
							}
						}

						if (fromConnection != null) {
							if (LOGGER.isDebugEnabled()) {
								String objectString = object == null ? "null" : object.getClass().getSimpleName();
								if (object instanceof FrameworkMessage) {
									LOGGER.trace("{}{} received UDP: {}", methodName, fromConnection, objectString);
								} else
									LOGGER.debug("{}{} received UDP: {}", methodName, fromConnection, objectString);
							}
							fromConnection.notifyReceived(object);
							continue;
						}
						LOGGER.debug("{} Ignoring UDP from unregistered address: {}", methodName, fromAddress);
					} catch (CancelledKeyException ex) {
						if (fromConnection != null)
							fromConnection.close();
						else
							selectionKey.channel().close();
					}
				}
			}
		}
		long time = System.currentTimeMillis();
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.tcp.isTimedOut(time)) {
				LOGGER.debug("{}{} timed out.", methodName, connection);
				connection.close();
			} else {
				if (connection.tcp.needsKeepAlive(time)) connection.sendTCP(FrameworkMessage.keepAlive);
			}
			if (connection.isIdle()) connection.notifyIdle();
		}
	}

	private void keepAlive () {
		long time = System.currentTimeMillis();
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.tcp.needsKeepAlive(time)) connection.sendTCP(FrameworkMessage.keepAlive);
		}
	}

	public void run () {
		final String methodName = "run : ";
		
		LOGGER.trace("{} Server thread started.", methodName);
		shutdown = false;
		while (!shutdown) {
			try {
				update(250);
			} catch (IOException ex) {
				LOGGER.error("Error updating server connections.", ex);
				close();
			}
		}
		LOGGER.trace("{} Server thread stopped.", methodName);
	}

	public void start () {
		new Thread(this, "Server").start();
	}

	public void stop () {
		if (shutdown) return;
		close();
		LOGGER.trace("stop : Server thread stopping.");
		shutdown = true;
	}

	private void acceptOperation (SocketChannel socketChannel) {
		Connection connection = newConnection();
		connection.initialize(serialization, writeBufferSize, objectBufferSize);
		connection.endPoint = this;
		UdpConnection udp = this.udp;
		if (udp != null) connection.udp = udp;
		try {
			SelectionKey selectionKey = connection.tcp.accept(selector, socketChannel);
			selectionKey.attach(connection);

			int id = nextConnectionID++;
			if (nextConnectionID == -1) nextConnectionID = 1;
			connection.id = id;
			connection.setConnected(true);
			connection.addListener(dispatchListener);

			if (udp == null)
				addConnection(connection);
			else
				pendingConnections.put(id, connection);

			RegisterTCP registerConnection = new RegisterTCP();
			registerConnection.connectionID = id;
			connection.sendTCP(registerConnection);

			if (udp == null) connection.notifyConnected();
		} catch (IOException ex) {
			connection.close();
			LOGGER.debug("acceptOperation : Unable to accept TCP connection.", ex);
		}
	}

	/** Allows the connections used by the server to be subclassed. This can be useful for storage per connection without an
	 * additional lookup. */
	protected Connection newConnection () {
		return new Connection();
	}

	private void addConnection (Connection connection) {
		Connection[] newConnections = new Connection[connections.length + 1];
		newConnections[0] = connection;
		System.arraycopy(connections, 0, newConnections, 1, connections.length);
		connections = newConnections;
	}

	void removeConnection (Connection connection) {
		ArrayList<Connection> temp = new ArrayList<Connection>(Arrays.asList(connections));
		temp.remove(connection);
		connections = temp.toArray(new Connection[temp.size()]);

		pendingConnections.remove(connection.id);
	}

	// BOZO - Provide mechanism for sending to multiple clients without serializing multiple times.

	public void sendToAllTCP (Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			connection.sendTCP(object);
		}
	}

	public void sendToAllExceptTCP (int connectionID, Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.id != connectionID) connection.sendTCP(object);
		}
	}

	public void sendToTCP (int connectionID, Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.id == connectionID) {
				connection.sendTCP(object);
				break;
			}
		}
	}

	public void sendToAllUDP (Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			connection.sendUDP(object);
		}
	}

	public void sendToAllExceptUDP (int connectionID, Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.id != connectionID) connection.sendUDP(object);
		}
	}

	public void sendToUDP (int connectionID, Object object) {
		Connection[] connections = this.connections;
		for (int i = 0, n = connections.length; i < n; i++) {
			Connection connection = connections[i];
			if (connection.id == connectionID) {
				connection.sendUDP(object);
				break;
			}
		}
	}

	public void addListener (Listener listener) {
		if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
		synchronized (listenerLock) {
			Listener[] listeners = this.listeners;
			int n = listeners.length;
			for (int i = 0; i < n; i++)
				if (listener == listeners[i]) return;
			Listener[] newListeners = new Listener[n + 1];
			newListeners[0] = listener;
			System.arraycopy(listeners, 0, newListeners, 1, n);
			this.listeners = newListeners;
		}
		LOGGER.trace("addListener : Server listener added: {}", listener.getClass().getName());
	}

	public void removeListener (Listener listener) {
		if (listener == null) throw new IllegalArgumentException("listener cannot be null.");
		synchronized (listenerLock) {
			Listener[] listeners = this.listeners;
			int n = listeners.length;
			Listener[] newListeners = new Listener[n - 1];
			for (int i = 0, ii = 0; i < n; i++) {
				Listener copyListener = listeners[i];
				if (listener == copyListener) continue;
				if (ii == n - 1) return;
				newListeners[ii++] = copyListener;
			}
			this.listeners = newListeners;
		}
		LOGGER.trace("removeListener : Server listener removed: {}" + listener.getClass().getName());
	}

	/** Closes all open connections and the server port(s). */
	public void close () {
		final String methodName = "close : ";
		
		Connection[] connections = this.connections;
		if (connections.length > 0) {
			LOGGER.info("{} Closing server connections...", methodName);
		}
		for (int i = 0, n = connections.length; i < n; i++)
			connections[i].close();
		connections = new Connection[0];

		ServerSocketChannel serverChannel = this.serverChannel;
		if (serverChannel != null) {
			try {
				serverChannel.close();
				LOGGER.info("{} Server closed.", methodName);
			} catch (IOException ex) {
				LOGGER.error("Unable to close server.", ex);
			}
			this.serverChannel = null;
		}

		UdpConnection udp = this.udp;
		if (udp != null) {
			udp.close();
			this.udp = null;
		}

		// Select one last time to complete closing the socket.
		synchronized (updateLock) {
			selector.wakeup();
			try {
				selector.selectNow();
			} catch (IOException ignored) {
			}
		}
	}

	/** Releases the resources used by this server, which may no longer be used. */
	public void dispose () throws IOException {
		close();
		selector.close();
	}

	public Thread getUpdateThread () {
		return updateThread;
	}

	/** Returns the current connections. The array returned should not be modified. */
	public Connection[] getConnections () {
		return connections;
	}
}
