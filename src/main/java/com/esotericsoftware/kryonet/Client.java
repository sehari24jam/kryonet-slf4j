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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.FrameworkMessage.DiscoverHost;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterTCP;
import com.esotericsoftware.kryonet.FrameworkMessage.RegisterUDP;

/** Represents a TCP and optionally a UDP connection to a {@link Server}.
 * @author Nathan Sweet <misc@n4te.com> */
public class Client extends Connection implements EndPoint {
	private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);
	
	static {
		try {
			// Needed for NIO selectors on Android 2.2.
			System.setProperty("java.net.preferIPv6Addresses", "false");
		} catch (AccessControlException ignored) {
		}
	}

	private final Serialization serialization;
	private Selector selector;
	private int emptySelects;
	private volatile boolean tcpRegistered, udpRegistered;
	private Object tcpRegistrationLock = new Object();
	private Object udpRegistrationLock = new Object();
	private volatile boolean shutdown;
	private final Object updateLock = new Object();
	private Thread updateThread;
	private int connectTimeout;
	private InetAddress connectHost;
	private int connectTcpPort;
	private int connectUdpPort;
	private boolean isClosed;
	private ClientDiscoveryHandler discoveryHandler;

	/** Creates a Client with a write buffer size of 8192 and an object buffer size of 2048. */
	public Client () {
		this(8192, 2048);
	}

	/** @param writeBufferSize One buffer of this size is allocated. Objects are serialized to the write buffer where the bytes are
	 *           queued until they can be written to the TCP socket.
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
	public Client (int writeBufferSize, int objectBufferSize) {
		this(writeBufferSize, objectBufferSize, new KryoSerialization());
	}

	public Client (int writeBufferSize, int objectBufferSize, Serialization serialization) {
		super();
		endPoint = this;

		this.serialization = serialization;

		this.discoveryHandler = ClientDiscoveryHandler.DEFAULT;

		initialize(serialization, writeBufferSize, objectBufferSize);

		try {
			selector = Selector.open();
		} catch (IOException ex) {
			throw new RuntimeException("Error opening selector.", ex);
		}
	}

	public void setDiscoveryHandler (ClientDiscoveryHandler newDiscoveryHandler) {
		discoveryHandler = newDiscoveryHandler;
	}

	public Serialization getSerialization () {
		return serialization;
	}

	public Kryo getKryo () {
		return ((KryoSerialization)serialization).getKryo();
	}

	/** Opens a TCP only client.
	 * @see #connect(int, InetAddress, int, int) */
	public void connect (int timeout, String host, int tcpPort) throws IOException {
		connect(timeout, InetAddress.getByName(host), tcpPort, -1);
	}

	/** Opens a TCP and UDP client.
	 * @see #connect(int, InetAddress, int, int) */
	public void connect (int timeout, String host, int tcpPort, int udpPort) throws IOException {
		connect(timeout, InetAddress.getByName(host), tcpPort, udpPort);
	}

	/** Opens a TCP only client.
	 * @see #connect(int, InetAddress, int, int) */
	public void connect (int timeout, InetAddress host, int tcpPort) throws IOException {
		connect(timeout, host, tcpPort, -1);
	}

	/** Opens a TCP and UDP client. Blocks until the connection is complete or the timeout is reached.
	 * <p>
	 * Because the framework must perform some minimal communication before the connection is considered successful,
	 * {@link #update(int)} must be called on a separate thread during the connection process.
	 * @throws IllegalStateException if called from the connection's update thread.
	 * @throws IOException if the client could not be opened or connecting times out. */
	public void connect (int timeout, InetAddress host, int tcpPort, int udpPort) throws IOException {
		final String methodName = "connect : ";
		
		if (host == null) throw new IllegalArgumentException("host cannot be null.");
		if (Thread.currentThread() == getUpdateThread())
			throw new IllegalStateException("Cannot connect on the connection's update thread.");
		this.connectTimeout = timeout;
		this.connectHost = host;
		this.connectTcpPort = tcpPort;
		this.connectUdpPort = udpPort;
		close();
		if (udpPort != -1) {
			LOGGER.info("{} Connecting: {}:{}/{}", methodName, host, tcpPort, udpPort);
		}
		else {
			LOGGER.info("{} Connecting: {}:{}", methodName, host, tcpPort);
		}
		id = -1;
		try {
			if (udpPort != -1) udp = new UdpConnection(serialization, tcp.readBuffer.capacity());

			long endTime;
			synchronized (updateLock) {
				tcpRegistered = false;
				selector.wakeup();
				endTime = System.currentTimeMillis() + timeout;
				tcp.connect(selector, new InetSocketAddress(host, tcpPort), 5000);
			}

			// Wait for RegisterTCP.
			synchronized (tcpRegistrationLock) {
				while (!tcpRegistered && System.currentTimeMillis() < endTime) {
					try {
						tcpRegistrationLock.wait(100);
					} catch (InterruptedException ignored) {
					}
				}
				if (!tcpRegistered) {
					throw new SocketTimeoutException("Connected, but timed out during TCP registration.\n"
						+ "Note: Client#update must be called in a separate thread during connect.");
				}
			}

			if (udpPort != -1) {
				InetSocketAddress udpAddress = new InetSocketAddress(host, udpPort);
				synchronized (updateLock) {
					udpRegistered = false;
					selector.wakeup();
					udp.connect(selector, udpAddress);
				}

				// Wait for RegisterUDP reply.
				synchronized (udpRegistrationLock) {
					while (!udpRegistered && System.currentTimeMillis() < endTime) {
						RegisterUDP registerUDP = new RegisterUDP();
						registerUDP.connectionID = id;
						udp.send(this, registerUDP, udpAddress);
						try {
							udpRegistrationLock.wait(100);
						} catch (InterruptedException ignored) {
						}
					}
					if (!udpRegistered)
						throw new SocketTimeoutException("Connected, but timed out during UDP registration: " + host + ":" + udpPort);
				}
			}
		} catch (IOException ex) {
			close();
			throw ex;
		}
	}

	/** Calls {@link #connect(int, InetAddress, int, int) connect} with the values last passed to connect.
	 * @throws IllegalStateException if connect has never been called. */
	public void reconnect () throws IOException {
		reconnect(connectTimeout);
	}

	/** Calls {@link #connect(int, InetAddress, int, int) connect} with the specified timeout and the other values last passed to
	 * connect.
	 * @throws IllegalStateException if connect has never been called. */
	public void reconnect (int timeout) throws IOException {
		if (connectHost == null) throw new IllegalStateException("This client has never been connected.");
		connect(timeout, connectHost, connectTcpPort, connectUdpPort);
	}

	/** Reads or writes any pending data for this client. Multiple threads should not call this method at the same time.
	 * @param timeout Wait for up to the specified milliseconds for data to be ready to process. May be zero to return immediately
	 *           if there is no data to process. */
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
			isClosed = false;
			Set<SelectionKey> keys = selector.selectedKeys();
			synchronized (keys) {
				for (Iterator<SelectionKey> iter = keys.iterator(); iter.hasNext();) {
					keepAlive();
					SelectionKey selectionKey = iter.next();
					iter.remove();
					try {
						int ops = selectionKey.readyOps();
						if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
							if (selectionKey.attachment() == tcp) {
								while (true) {
									Object object = tcp.readObject(this);
									if (object == null) break;
									if (!tcpRegistered) {
										if (object instanceof RegisterTCP) {
											id = ((RegisterTCP)object).connectionID;
											synchronized (tcpRegistrationLock) {
												tcpRegistered = true;
												tcpRegistrationLock.notifyAll();
												LOGGER.trace("{}{} received TCP: RegisterTCP", methodName, this);
												if (udp == null) setConnected(true);
											}
											if (udp == null) notifyConnected();
										}
										continue;
									}
									if (udp != null && !udpRegistered) {
										if (object instanceof RegisterUDP) {
											synchronized (udpRegistrationLock) {
												udpRegistered = true;
												udpRegistrationLock.notifyAll();
												LOGGER.trace("{}{} received UDP: RegisterUDP", methodName, this);
												LOGGER.debug("{} Port {}/UDP connected to: {}", methodName, udp.datagramChannel.socket().getLocalPort(),
														udp.connectedAddress);
												setConnected(true);
											}
											notifyConnected();
										}
										continue;
									}
									if (!isConnected) continue;
									if (LOGGER.isDebugEnabled()) {
										String objectString = object == null ? "null" : object.getClass().getSimpleName();
										if (!(object instanceof FrameworkMessage)) {
											LOGGER.debug("{}{} received TCP: {}", methodName, this, objectString);
										} else {
											LOGGER.trace("{}{} received TCP: {}", methodName, this, objectString);
										}
									}
									notifyReceived(object);
								}
							} else {
								if (udp.readFromAddress() == null) continue;
								Object object = udp.readObject(this);
								if (object == null) continue;
								if (LOGGER.isDebugEnabled()) {
									String objectString = object == null ? "null" : object.getClass().getSimpleName();
									LOGGER.debug("{}{} received UDP: {}", methodName, this, objectString);
								}
								notifyReceived(object);
							}
						}
						if ((ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) tcp.writeOperation();
					} catch (CancelledKeyException ignored) {
						// Connection is closed.
					}
				}
			}
		}
		if (isConnected) {
			long time = System.currentTimeMillis();
			if (tcp.isTimedOut(time)) {
				LOGGER.debug("{}{} timed out.", methodName, this);
				close();
			} else
				keepAlive();
			if (isIdle()) notifyIdle();
		}
	}

	void keepAlive () {
		if (!isConnected) return;
		long time = System.currentTimeMillis();
		if (tcp.needsKeepAlive(time)) sendTCP(FrameworkMessage.keepAlive);
		if (udp != null && udpRegistered && udp.needsKeepAlive(time)) sendUDP(FrameworkMessage.keepAlive);
	}

	public void run () {
		final String methodName = "run : ";
		
		LOGGER.trace("{} Client thread started.", methodName);
		shutdown = false;
		while (!shutdown) {
			try {
				update(250);
			} catch (IOException ex) {
				if (LOGGER.isTraceEnabled()) {
					if (isConnected)
						LOGGER.trace("Unable to update connection: " + this, ex);
					else
						LOGGER.trace("Unable to update connection.", ex);
				} else if (LOGGER.isDebugEnabled()) {
					if (isConnected)
						LOGGER.debug("{}{} update: {}", methodName, this, ex.getMessage());
					else
						LOGGER.debug("{} Unable to update connection: {}",methodName, ex.getMessage());
				}
				close();
			} catch (KryoNetException ex) {
				if (isConnected)
					LOGGER.error("Error updating connection: " + this, ex);
				else
					LOGGER.error("Error updating connection.", ex);
				close();
				throw ex;
			}
		}
		LOGGER.trace("{} Client thread stopped.", methodName);
	}

	public void start () {
		// Try to let any previous update thread stop.
		if (updateThread != null) {
			shutdown = true;
			try {
				updateThread.join(5000);
			} catch (InterruptedException ignored) {
			}
		}
		updateThread = new Thread(this, "Client");
		updateThread.setDaemon(true);
		updateThread.start();
	}

	public void stop () {
		if (shutdown) return;
		close();
		LOGGER.trace("stop : Client thread stopping.");
		shutdown = true;
		selector.wakeup();
	}

	public void close () {
		super.close();
		// Select one last time to complete closing the socket.
		synchronized (updateLock) {
			if (!isClosed) {
				isClosed = true;
				selector.wakeup();
				try {
					selector.selectNow();
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** Releases the resources used by this client, which may no longer be used. */
	public void dispose () throws IOException {
		close();
		selector.close();
	}

	public void addListener (Listener listener) {
		super.addListener(listener);
		LOGGER.trace("addListener : Client listener added.");
	}

	public void removeListener (Listener listener) {
		super.removeListener(listener);
		LOGGER.trace("removeListener : Client listener removed.");
	}

	/** An empty object will be sent if the UDP connection is inactive more than the specified milliseconds. Network hardware may
	 * keep a translation table of inside to outside IP addresses and a UDP keep alive keeps this table entry from expiring. Set to
	 * zero to disable. Defaults to 19000. */
	public void setKeepAliveUDP (int keepAliveMillis) {
		if (udp == null) throw new IllegalStateException("Not connected via UDP.");
		udp.keepAliveMillis = keepAliveMillis;
	}

	public Thread getUpdateThread () {
		return updateThread;
	}

	private void broadcast (int udpPort, DatagramSocket socket) throws IOException {
		ByteBuffer dataBuffer = ByteBuffer.allocate(64);
		serialization.write(null, dataBuffer, new DiscoverHost());
		dataBuffer.flip();
		byte[] data = new byte[dataBuffer.limit()];
		dataBuffer.get(data);
		for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			for (InetAddress address : Collections.list(iface.getInetAddresses())) {
				// Java 1.5 doesn't support getting the subnet mask, so try the two most common.
				byte[] ip = address.getAddress();
				ip[3] = -1; // 255.255.255.0
				try {
					socket.send(new DatagramPacket(data, data.length, InetAddress.getByAddress(ip), udpPort));
				} catch (Exception ignored) {
				}
				ip[2] = -1; // 255.255.0.0
				try {
					socket.send(new DatagramPacket(data, data.length, InetAddress.getByAddress(ip), udpPort));
				} catch (Exception ignored) {
				}
			}
		}
		LOGGER.debug("broadcast : Broadcasted host discovery on port: {}", udpPort);
	}

	/** Broadcasts a UDP message on the LAN to discover any running servers. The address of the first server to respond is returned.
	 * @param udpPort The UDP port of the server.
	 * @param timeoutMillis The number of milliseconds to wait for a response.
	 * @return the first server found, or null if no server responded. */
	public InetAddress discoverHost (int udpPort, int timeoutMillis) {
		final String methodName = "discoverHost : ";
		
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
			broadcast(udpPort, socket);
			socket.setSoTimeout(timeoutMillis);
			DatagramPacket packet = discoveryHandler.onRequestNewDatagramPacket();
			try {
				socket.receive(packet);
			} catch (SocketTimeoutException ex) {
				LOGGER.info("{} Host discovery timed out.", methodName);
				return null;
			}
			LOGGER.info("{} Discovered server: {}", methodName, packet.getAddress());
			discoveryHandler.onDiscoveredHost(packet, getKryo());
			return packet.getAddress();
		} catch (IOException ex) {
			LOGGER.error("Host discovery failed.", ex);
			return null;
		} finally {
			if (socket != null) socket.close();
			discoveryHandler.onFinally();
		}
	}

	/** Broadcasts a UDP message on the LAN to discover any running servers.
	 * @param udpPort The UDP port of the server.
	 * @param timeoutMillis The number of milliseconds to wait for a response. */
	public List<InetAddress> discoverHosts (int udpPort, int timeoutMillis) {
		final String methodName = "discoverHosts : ";
		
		List<InetAddress> hosts = new ArrayList<InetAddress>();
		DatagramSocket socket = null;
		try {
			socket = new DatagramSocket();
			broadcast(udpPort, socket);
			socket.setSoTimeout(timeoutMillis);
			while (true) {
				DatagramPacket packet = discoveryHandler.onRequestNewDatagramPacket();
				try {
					socket.receive(packet);
				} catch (SocketTimeoutException ex) {
					LOGGER.info("{} Host discovery timed out.", methodName);
					return hosts;
				}
				LOGGER.info("{} Discovered server: {}", methodName, packet.getAddress());
				discoveryHandler.onDiscoveredHost(packet, getKryo());
				hosts.add(packet.getAddress());
			}
		} catch (IOException ex) {
			LOGGER.error("Host discovery failed.", ex);
			return hosts;
		} finally {
			if (socket != null) socket.close();
			discoveryHandler.onFinally();
		}
	}
}
