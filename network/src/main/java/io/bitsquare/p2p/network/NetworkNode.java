package io.bitsquare.p2p.network;

import com.google.common.util.concurrent.*;
import io.bitsquare.app.Log;
import io.bitsquare.common.UserThread;
import io.bitsquare.common.util.Utilities;
import io.bitsquare.p2p.Address;
import io.bitsquare.p2p.Message;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.google.common.base.Preconditions.checkNotNull;

// Run in UserThread
public abstract class NetworkNode implements MessageListener, ConnectionListener {
    private static final Logger log = LoggerFactory.getLogger(NetworkNode.class);

    private static final int CREATE_SOCKET_TIMEOUT = 1 * 1000;        // 10 sec.

    protected final int servicePort;

    private final CopyOnWriteArraySet<Connection> inBoundConnections = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<MessageListener> messageListeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<ConnectionListener> connectionListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<SetupListener> setupListeners = new CopyOnWriteArraySet<>();
    protected ListeningExecutorService executorService;
    private Server server;
    private ConnectionListener startServerConnectionListener;

    private volatile boolean shutDownInProgress;
    // accessed from different threads
    private final CopyOnWriteArraySet<Connection> outBoundConnections = new CopyOnWriteArraySet<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public NetworkNode(int servicePort) {
        Log.traceCall();
        this.servicePort = servicePort;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void start() {
        Log.traceCall();
        start(null);
    }

    abstract public void start(@Nullable SetupListener setupListener);

    public SettableFuture<Connection> sendMessage(@NotNull Address peerAddress, Message message) {
        Log.traceCall("message: " + message + " to peerAddress: " + peerAddress);
        checkNotNull(peerAddress, "peerAddress must not be null");

        Optional<Connection> outboundConnectionOptional = lookupOutboundConnection(peerAddress);
        Connection connection = outboundConnectionOptional.isPresent() ? outboundConnectionOptional.get() : null;
        if (connection != null)
            log.trace("We have found a connection in outBoundConnections. Connection.uid=" + connection.getUid());

        if (connection != null && connection.isStopped()) {
            log.trace("We have a connection which is already stopped in outBoundConnections. Connection.uid=" + connection.getUid());
            outBoundConnections.remove(connection);
            connection = null;
        }

        if (connection == null) {
            Optional<Connection> inboundConnectionOptional = lookupInboundConnection(peerAddress);
            if (inboundConnectionOptional.isPresent()) connection = inboundConnectionOptional.get();
            if (connection != null)
                log.trace("We have found a connection in inBoundConnections. Connection.uid=" + connection.getUid());
        }

        if (connection != null) {
            return sendMessage(connection, message);
        } else {
            log.trace("We have not found any connection for that peerAddress. " +
                    "We will create a new outbound connection.");

            final SettableFuture<Connection> resultFuture = SettableFuture.create();
            ListenableFuture<Connection> future = executorService.submit(() -> {
                Thread.currentThread().setName("NetworkNode:SendMessage-to-" + peerAddress);
                try {
                    Socket socket = createSocket(peerAddress); // can take a while when using tor
                    Connection newConnection = new Connection(socket, NetworkNode.this, NetworkNode.this);
                    newConnection.setPeerAddress(peerAddress);
                    outBoundConnections.add(newConnection);

                    log.info("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                            "NetworkNode created new outbound connection:"
                            + "\npeerAddress=" + peerAddress
                            + "\nconnection.uid=" + newConnection.getUid()
                            + "\nmessage=" + message
                            + "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");

                    newConnection.sendMessage(message);
                    return newConnection; // can take a while when using tor
                } catch (Throwable throwable) {
                    if (!(throwable instanceof ConnectException || throwable instanceof IOException)) {
                        throwable.printStackTrace();
                        log.error("Executing task failed. " + throwable.getMessage());
                    }
                    throw throwable;
                }
            });
            Futures.addCallback(future, new FutureCallback<Connection>() {
                public void onSuccess(Connection connection) {
                    UserThread.execute(() -> {
                        resultFuture.set(connection);
                    });
                }

                public void onFailure(@NotNull Throwable throwable) {
                    UserThread.execute(() -> {
                        resultFuture.setException(throwable);
                    });
                }
            });
            return resultFuture;
        }
    }

    public SettableFuture<Connection> sendMessage(Connection connection, Message message) {
        Log.traceCall();
        // connection.sendMessage might take a bit (compression, write to stream), so we use a thread to not block
        ListenableFuture<Connection> future = executorService.submit(() -> {
            Thread.currentThread().setName("NetworkNode:SendMessage-to-" + connection.objectId);
            try {
                connection.sendMessage(message);
                return connection;
            } catch (Throwable t) {
                throw t;
            }
        });
        final SettableFuture<Connection> resultFuture = SettableFuture.create();
        Futures.addCallback(future, new FutureCallback<Connection>() {
            public void onSuccess(Connection connection) {
                UserThread.execute(() -> {
                    resultFuture.set(connection);
                });
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> {
                    resultFuture.setException(throwable);
                });
            }
        });
        return resultFuture;
    }

    public Set<Connection> getAllConnections() {
        Log.traceCall();
        Set<Connection> set = new HashSet<>(inBoundConnections);
        set.addAll(outBoundConnections);
        return set;
    }

    public void shutDown(Runnable shutDownCompleteHandler) {
        Log.traceCall();
        log.info("Shutdown NetworkNode");
        if (!shutDownInProgress) {
            shutDownInProgress = true;
            if (server != null) {
                server.shutDown();
                server = null;
            }

            getAllConnections().stream().forEach(e -> e.shutDown());

            log.info("NetworkNode shutdown complete");
            if (shutDownCompleteHandler != null) shutDownCompleteHandler.run();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // SetupListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addSetupListener(SetupListener setupListener) {
        Log.traceCall();
        setupListeners.add(setupListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
        Log.traceCall();
        connectionListeners.stream().forEach(e -> e.onConnection(connection));
    }

    @Override
    public void onPeerAddressAuthenticated(Address peerAddress, Connection connection) {
        Log.traceCall();
        log.trace("onAuthenticationComplete peerAddress/connection: " + peerAddress + " / " + connection);

        connectionListeners.stream().forEach(e -> e.onPeerAddressAuthenticated(peerAddress, connection));
    }

    @Override
    public void onDisconnect(Reason reason, Connection connection) {
        Log.traceCall();
        Address peerAddress = connection.getPeerAddress();
        log.trace("onDisconnect connection " + connection + ", peerAddress= " + peerAddress);
        outBoundConnections.remove(connection);
        inBoundConnections.remove(connection);
        connectionListeners.stream().forEach(e -> e.onDisconnect(reason, connection));
    }

    @Override
    public void onError(Throwable throwable) {
        Log.traceCall();
        connectionListeners.stream().forEach(e -> e.onError(throwable));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(Message message, Connection connection) {
        Log.traceCall();
        messageListeners.stream().forEach(e -> e.onMessage(message, connection));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addConnectionListener(ConnectionListener connectionListener) {
        Log.traceCall();
        connectionListeners.add(connectionListener);
    }

    public void removeConnectionListener(ConnectionListener connectionListener) {
        Log.traceCall();
        connectionListeners.remove(connectionListener);
    }

    public void addMessageListener(MessageListener messageListener) {
        Log.traceCall();
        messageListeners.add(messageListener);
    }

    public void removeMessageListener(MessageListener messageListener) {
        Log.traceCall();
        messageListeners.remove(messageListener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void createExecutorService() {
        Log.traceCall();
        executorService = Utilities.getListeningExecutorService("NetworkNode-" + servicePort, 20, 50, 120L);
    }

    protected void startServer(ServerSocket serverSocket) {
        Log.traceCall();
        startServerConnectionListener = new ConnectionListener() {
            @Override
            public void onConnection(Connection connection) {
                Log.traceCall();
                // we still have not authenticated so put it to the temp list
                inBoundConnections.add(connection);
                NetworkNode.this.onConnection(connection);
            }

            @Override
            public void onPeerAddressAuthenticated(Address peerAddress, Connection connection) {
                Log.traceCall();
                NetworkNode.this.onPeerAddressAuthenticated(peerAddress, connection);
            }

            @Override
            public void onDisconnect(Reason reason, Connection connection) {
                Log.traceCall();
                Address peerAddress = connection.getPeerAddress();
                log.trace("onDisconnect at incoming connection to peerAddress (or connection) "
                        + ((peerAddress == null) ? connection : peerAddress));
                inBoundConnections.remove(connection);
                NetworkNode.this.onDisconnect(reason, connection);
            }

            @Override
            public void onError(Throwable throwable) {
                Log.traceCall();
                NetworkNode.this.onError(throwable);
            }
        };
        server = new Server(serverSocket,
                NetworkNode.this,
                startServerConnectionListener);
        executorService.submit(server);
    }

    private Optional<Connection> lookupOutboundConnection(Address peerAddress) {
        Log.traceCall();
        return outBoundConnections.stream()
                .filter(e -> peerAddress.equals(e.getPeerAddress())).findAny();
    }

    private Optional<Connection> lookupInboundConnection(Address peerAddress) {
        Log.traceCall();
        return inBoundConnections.stream()
                .filter(e -> peerAddress.equals(e.getPeerAddress())).findAny();
    }

    abstract protected Socket createSocket(Address peerAddress) throws IOException;

    @Nullable
    abstract public Address getAddress();
}