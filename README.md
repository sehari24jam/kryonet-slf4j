KryoNet-slf4j is a fork of [KryoNet](https://github.com/EsotericSoftware/kryonet), a Java library that provides a clean and simple API for efficient TCP and UDP client/server network communication using NIO. KryoNet uses the [Kryo serialization library](https://github.com/EsotericSoftware/kryo) to automatically and efficiently transfer object graphs across the network.

KryoNet runs on both the desktop and on [Android](http://www.android.com/).

KryoNet-slf4j is a fork of KryoNet that replaces the Minlog logging used by KryoNet with slf4j providing users the choice to pick their desired logging implementation instead of having to deal with multiple logging implementations when using KryoNet in projects using a different logging implementation.

KryoNet-slf4j also decouples the explicit dependency on jsonbeans letting users pick their own json implementations.

KryoNet-slf4j is built off v2.22.0-RC1 of KryoNet and accordingly tracks the version. Please refer to the [KryoNet](https://github.com/EsotericSoftware/kryonet) project page for detailed documentation on the KryoNet API and its usage.

