package com.dori.SpringStory.connection.netty;

import com.dori.SpringStory.client.MapleClient;
import com.dori.SpringStory.client.character.MapleChar;
import com.dori.SpringStory.connection.packet.Handler;
import com.dori.SpringStory.connection.packet.InPacket;
import com.dori.SpringStory.connection.packet.headers.InHeader;
import com.dori.SpringStory.constants.ServerConstants;
import com.dori.SpringStory.logger.Logger;
import com.dori.SpringStory.utils.MapleUtils;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.dori.SpringStory.connection.netty.NettyClient.CLIENT_KEY;


public class ChannelHandler extends SimpleChannelInboundHandler<InPacket> {

    private static final Map<InHeader, Method> handlers = new HashMap<>();
    private static final Logger logger = new Logger(ChannelHandler.class);

    public static void initHandlers(boolean mayOverride) {
        long start = System.currentTimeMillis();
        String handlersDir = ServerConstants.HANDLERS_DIR;
        Set<File> files = new HashSet<>();
        MapleUtils.findAllFilesInDirectory(files, new File(handlersDir));
        for (File file : files) {
            try {
                // grab all files in the handlers' dir, strip them to their package name, and remove .java extension
                String className = file.getPath()
                        .replaceAll("[\\\\|/]", ".")
                        .split("src\\.main\\.java\\.")[1]
                        .replaceAll("\\.java", "");
                Class<?> clazz = Class.forName(className);
                for (Method method : clazz.getMethods()) {
                    Handler handler = method.getAnnotation(Handler.class);
                    if (handler != null) {
                        InHeader header = handler.op();
                        if (header != InHeader.NO) {
                            if (handlers.containsKey(header) && !mayOverride) {
                                throw new IllegalArgumentException(String.format("Multiple handlers found for header %s! " +
                                        "Had method %s, but also found %s.", header, handlers.get(header).getName(), method.getName()));
                            }
                            handlers.put(header, method);
                        }
                        InHeader[] headers = handler.ops();
                        for (InHeader h : headers) {
                            handlers.put(h, method);
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        logger.serverNotice("Initialized " + handlers.size() + " handlers in " + (System.currentTimeMillis() - start) + "ms.");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Channel inactive.");
        MapleClient c = (MapleClient) ctx.channel().attr(CLIENT_KEY).get();
        if (c != null && c.getChr() != null) {
            c.logout();
        } else if (c != null) {
            logger.warning("Migration (login/out) with a client that don't have a chr attach! | IP: " + c.getIP());
        } else {
            logger.warning("Was not able to save character, data inconsistency may have occurred.");
        }
        NettyClient o = ctx.channel().attr(CLIENT_KEY).get();
        if (o != null) {
            o.close();
        }
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, InPacket inPacket) {
        MapleClient c = (MapleClient) ctx.channel().attr(CLIENT_KEY).get();
        MapleChar chr = c.getChr();
        short op = inPacket.decodeShort();
        InHeader inHeader = InHeader.getInHeaderByOp(op);
        if (inHeader == null) {
            handleUnknown(inPacket, op);
            return;
        }
        if (!InHeader.isSpamHeader(InHeader.getInHeaderByOp(op))) {
            logger.receive(String.valueOf(op), "0x" + Integer.toHexString(op).toUpperCase(), InHeader.getInHeaderByOp(op).name(), inPacket.toString());
        }
        Method method = handlers.get(inHeader);
        try {
            if (method == null) {
                handleUnknown(inPacket, op);
            } else {
                Class<?> clazz = method.getParameterTypes()[0];
                try {
                    if (method.getParameterTypes().length == 3) {
                        method.invoke(this, c, inPacket, inHeader);
                    } else if (clazz == MapleClient.class) {
                        method.invoke(this, c, inPacket);
                    } else if (clazz == MapleChar.class) {
                        method.invoke(this, chr, inPacket);
                    } else {
                        logger.error("Unhandled first param type of handler " + method.getName() + ", type = " + clazz);
                    }
                } catch (IllegalAccessException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        } finally {
            inPacket.release();
        }
    }

    private void handleUnknown(InPacket inPacket, short opCode) {
        if (!InHeader.isSpamHeader(InHeader.getInHeaderByOp(opCode))) {
            logger.error(String.format("Unhandled opcode %s %s/0x%s, packet %s", InHeader.getInHeaderByOp(opCode), opCode, Integer.toHexString(opCode).toUpperCase(), inPacket));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof IOException) {
            logger.info("Client forcibly closed the game.");
        } else {
            cause.printStackTrace();
        }
    }
}
