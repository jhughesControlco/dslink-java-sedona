package org.dsa.iot.sedona;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.SubscriptionManager;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import sedona.Slot;
import sedona.dasp.DaspSocket;
import sedona.sox.SoxClient;
import sedona.sox.SoxComponent;
import sedona.sox.SoxComponentListener;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author Samuel Grenier
 */
public class Sedona {

    private static final Logger LOGGER = LoggerFactory.getLogger(Sedona.class);
    private final SubscriptionManager manager;
    private final Node parent;

    private ScheduledFuture<?> future;
    private SoxClient client;

    public Sedona(Node parent, SubscriptionManager manager) {
        this.manager = manager;
        this.parent = parent;
    }

    public synchronized void connect(boolean checked) {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (client == null) {
            String url = parent.getRoConfig("url").getString();
            int port = parent.getRoConfig("port").getNumber().intValue();
            String user = parent.getRoConfig("username").getString();
            char[] pass = parent.getPassword();

            try {
                int queue = DaspSocket.SESSION_QUEUING;
                DaspSocket socket = DaspSocket.open(-1, null, queue);
                InetAddress ina = InetAddress.getByName(url);
                String password = "";
                if (pass != null) {
                    password = new String(pass);
                }
                client = new SoxClient(socket, ina, port, user, password);
                client.connect();
                LOGGER.info("Opened connection to '{}'", parent.getName());
                try {
                    SoxComponent top = client.loadApp();
                    buildTree(parent, top);
                    client.subscribeToAllTreeEvents();
                } catch (Exception e) {
                    LOGGER.error("Failed to build tree", e);
                }
            } catch (Exception e) {
                if (checked) {
                    throw new RuntimeException(e);
                }
                scheduleReconnect();
            }
        }
    }

    public void invoke(SoxComponent component, Slot slot, sedona.Value value) {
        try {
            client.invoke(component, slot, value);
        } catch (Exception e) {
            LOGGER.error("Error invoking", e);
        }
    }

    private void scheduleReconnect() {
        LOGGER.warn("Reconnection to Sedona server scheduled");
        client = null;
        future = Objects.getDaemonThreadPool().schedule(new Runnable() {
            @Override
            public void run() {
                connect(false);
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void buildTree(final Node parent,
                           final SoxComponent comp) {
        Objects.getDaemonThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                String name = comp.name();
                NodeBuilder builder = getOrCreateBuilder(parent, name);

                if (comp.listener == null) {
                    comp.listener = new SoxComponentListener() {
                        @Override
                        public void changed(SoxComponent c, int mask) {
                            buildTree(parent, c);
                        }
                    };
                }

                final Node node = builder.build();
                node.setSerializable(false);

                for (Slot slot : comp.type.slots) {
                    Node n = node.createChild(slot.name).build();
                    if (slot.isAction()) {
                        Sedona s = Sedona.this;
                        Action a = Actions.getInvokableSedonaNode(s, slot, comp);
                        n.setAction(a);
                    } else {
                        sedona.Value val = comp.get(slot);
                        Value value = new Value((String) null, true);
                        if (val != null) {
                            value.set(val.toString());
                        }

                        n.setValue(value);
                        setSubHandlers(n, comp);
                    }
                }

                SoxComponent[] children = comp.children();
                if (children != null) {
                    for (SoxComponent c : children) {
                        buildTree(node, c);
                    }
                }
            }
        });
    }

    private void setSubHandlers(final Node child,
                                final SoxComponent component) {
        child.getListener().setOnSubscribeHandler(new Handler<Node>() {
            @Override
            public void handle(Node event) {
                try {
                    int mask = SoxComponent.RUNTIME;
                    if ((component.subscription() & mask) != mask) {
                        LOGGER.info("Subscribed to {}", child.getPath());
                        mask = SoxComponent.RUNTIME | SoxComponent.CONFIG;
                        client.subscribe(component, mask);
                    }
                } catch (Exception e) {
                    LOGGER.error("", e);
                }
            }
        });

        child.getListener().setOnUnsubscribeHandler(new Handler<Node>() {
            @Override
            public void handle(Node event) {
                try {
                    Map<String, Node> children = child.getParent().getChildren();
                    if (children != null) {
                        for (Node child : children.values()) {
                            if (child.getValue() != null
                                    && manager.hasValueSub(child)) {
                                return;
                            }
                        }
                    }

                    int mask = SoxComponent.RUNTIME;
                    if ((component.subscription() & mask) == mask) {
                        LOGGER.info("Unsubscribed to {}", child.getPath());
                        mask = SoxComponent.RUNTIME | SoxComponent.CONFIG;
                        client.unsubscribe(component, mask);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to unsubscribe", e);
                }
            }
        });
    }

    public static void init(Node superRoot, SubscriptionManager manager) {
        {
            NodeBuilder child = superRoot.createChild("addServer");
            child.setAction(Actions.getAddServerAction(superRoot, manager));
            child.build();
        }

        {
            Map<String, Node> children = superRoot.getChildren();
            if (children != null) {
                for (Node child : children.values()) {
                    if (child.getAction() == null) {
                        Sedona sedona = new Sedona(child, manager);
                        sedona.connect(false);
                    }
                }
            }
        }
    }

    private static NodeBuilder getOrCreateBuilder(Node parent, String name) {
        NodeBuilder builder;
        {
            Node node = parent.getChild(name);
            if (node == null) {
                builder = parent.createChild(name);
            } else {
                builder = node.createFakeBuilder();
            }
        }
        return builder;
    }
}
