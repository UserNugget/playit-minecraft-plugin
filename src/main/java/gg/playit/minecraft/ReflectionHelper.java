package gg.playit.minecraft;

import io.netty.channel.*;
import io.netty.util.AttributeKey;
import org.bukkit.Server;

import java.lang.reflect.*;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ReflectionHelper {
    static Logger log = Logger.getLogger(ReflectionHelper.class.getName());

    private final Class<ChannelHandler> ServerBootstrapAcceptor;

    private final Class<?> ServerConnection;
    private final Class<?> MinecraftServer;

    private final Class<?> CraftServer;

    public ReflectionHelper() {
        ServerBootstrapAcceptor = (Class<ChannelHandler>) cls(
                "io.netty.bootstrap.ServerBootstrap$ServerBootstrapAcceptor"
        );
        ServerConnection = cls("net.minecraft.server.network.ServerConnection");
        MinecraftServer = cls("net.minecraft.server.MinecraftServer");
        CraftServer = cls(
                "org.bukkit.craftbukkit.CraftServer",
                "org.bukkit.craftbukkit.v1_19_R1.CraftServer"
        );
    }

    static Class<?> cls(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    static Class<?> cls(String... classNames) {
        for (var name : classNames) {
            var res = cls(name);
            if (res != null) {
                return res;
            }
        }
        return null;
    }

    public boolean setRemoteAddress(Channel channel, SocketAddress address) {
        try {
            Field field = AbstractChannel.class.getDeclaredField("remoteAddress");
            field.setAccessible(true);
            field.set(channel, address);
            return true;
        } catch (Exception error) {
            log.warning("failed to set remoteAddress, error: " + error);
            return false;
        }
    }

    public ServerChannel findServerChannel(Object serverConnection) {
        for (Field field : searchForFieldByType(serverConnection.getClass(), List.class)) {
            try {
                field.setAccessible(true);
                List<?> list = (List<?>) field.get(serverConnection);
                if (list != null && list.size() > 0 && list.get(0) instanceof ChannelFuture future &&
                        future.isSuccess() && future.channel() instanceof ServerChannel) {
                    return (ServerChannel) future.channel();
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("should not reach here", e);
            }
        }

        return null;
    }


    public ChannelHandler findServerHandler(ServerChannel serverChannel) {
        return serverChannel.pipeline().get(ServerBootstrapAcceptor);
    }

    public ChannelHandler findChildHandler(ChannelHandler serverAcceptor) {
        try {
            Field childHandler = searchForFieldByName(ServerBootstrapAcceptor, "childHandler");
            childHandler.setAccessible(true);
            return (ChannelHandler) childHandler.get(serverAcceptor);
        } catch (NoSuchFieldException | IllegalAccessException error) {
            log.warning("failed to get childHandler, error: " + error);
            return null;
        }
    }

    public Map.Entry<ChannelOption<Object>, Object>[] findChildOptions(ChannelHandler serverAcceptor) {
        try {
            Field childOptions = searchForFieldByName(ServerBootstrapAcceptor, "childOptions");
            childOptions.setAccessible(true);
            return (Map.Entry<ChannelOption<Object>, Object>[]) childOptions.get(serverAcceptor);
        } catch (NoSuchFieldException | IllegalAccessException error) {
            log.warning("failed to get childOptions, error: " + error);
            return null;
        }
    }

    public Map.Entry<AttributeKey<Object>, Object>[] findChildAttrs(ChannelHandler serverAcceptor) {
        try {
            Field childAttrs = searchForFieldByName(ServerBootstrapAcceptor, "childAttrs");
            childAttrs.setAccessible(true);
            return (Map.Entry<AttributeKey<Object>, Object>[]) childAttrs.get(serverAcceptor);
        } catch (NoSuchFieldException | IllegalAccessException error) {
            log.warning("failed to get childAttrs, error: " + error);
            return null;
        }
    }

    public Object getMinecraftServer(Server server) {
        if (MinecraftServer == null) {
            return null;
        }
        if (MinecraftServer.isInstance(server)) {
            return server;
        }

        if (CraftServer != null) {
            try {
                Method method = searchMethod(CraftServer, "getServer");
                method.setAccessible(true);

                Object mcServer = method.invoke(server);
                if (MinecraftServer.isInstance(mcServer)) {
                    return mcServer;
                }
            } catch (Exception ignore) {
            }

            try {
                var field = searchForFieldByName(CraftServer, "console");
                field.setAccessible(true);
                Object mcServer = field.get(server);
                if (MinecraftServer.isInstance(mcServer)) {
                    return mcServer;
                }
            } catch (Exception ignore) {
            }
        }


        return null;
    }

    public Object serverConnectionFromMCServer(Object object) {
        if (object == null || MinecraftServer == null) {
            return null;
        }

        try {
            Method getConnection = searchMethod(MinecraftServer, "getConnection");
            getConnection.setAccessible(true);
            var res = getConnection.invoke(object);
            if (ServerConnection.isInstance(res)) {
                return res;
            }
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException ignore) {
        }

        try {
            var field = MinecraftServer.getDeclaredField("connection");
            field.setAccessible(true);
            var res = field.get(object);
            if (ServerConnection.isInstance(res)) {
                return res;
            }
        } catch (Exception e) {
        }

        return searchForAttribute(MinecraftServer, ServerConnection, object);
    }

    public Method searchMethod(Class<?> subject, String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        try {
            return subject.getMethod(name, parameterTypes);
        } catch (Exception ignore) {
        }

        while (subject != null) {
            try {
                return subject.getDeclaredMethod(name, parameterTypes);
            } catch (Exception ignore) {
            }

            for (var method : subject.getDeclaredMethods()) {
                Class<?>[] searchParamTypes = method.getParameterTypes();
                if (!method.getName().equals(name) || searchParamTypes.length != parameterTypes.length) {
                    continue;
                }

                boolean match = true;
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (!searchParamTypes[i].isAssignableFrom(parameterTypes[i])) {
                        match = false;
                        break;
                    }
                }

                if (match) {
                    return method;
                }
            }

            subject = subject.getSuperclass();
        }

        throw new NoSuchMethodException(name);
    }

    public Object searchForAttribute(Class<?> parent, Class<?> child, Object subject) {
        for (var field : parent.getFields()) {
            if (child.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    var res = field.get(subject);
                    if (child.isInstance(res)) {
                        return res;
                    }
                } catch (Exception ignore) {
                }
            }
        }

        for (var field : parent.getDeclaredFields()) {
            if (child.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    var res = field.get(subject);
                    if (child.isInstance(res)) {
                        return res;
                    }
                } catch (Exception ignore) {
                }
            }
        }

        return null;
    }

    public Field searchForFieldByName(Class<?> subject, String name) throws NoSuchFieldException {
        while (subject != null) {
            try {
                return subject.getDeclaredField(name);
            } catch (Exception ignore) {
            }
            subject = subject.getSuperclass();
        }

        throw new NoSuchFieldException(name);
    }

    public List<Field> searchForFieldByType(Class<?> subject, Class<?> type) {
        var fields = new ArrayList<Field>();

        while (subject != null) {
            try {
                for (var f : subject.getDeclaredFields()) {
                    if (type.isAssignableFrom(f.getType())) {
                        fields.add(f);
                    }
                }
            } catch (Exception ignore) {
            }

            subject = subject.getSuperclass();
        }

        return fields;
    }

    @Override
    public String toString() {
        return "ReflectionHelper{" +
                "ServerBootstrapAcceptor=" + ServerBootstrapAcceptor +
                ", ServerConnection=" + ServerConnection +
                ", MinecraftServer=" + MinecraftServer +
                ", CraftServer=" + CraftServer +
                '}';
    }
}
