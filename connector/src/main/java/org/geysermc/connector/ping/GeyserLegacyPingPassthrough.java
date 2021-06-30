/*
 * Copyright (c) 2019-2021 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.connector.ping;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.github.steveice10.mc.protocol.MinecraftConstants;
import com.nukkitx.nbt.util.VarInts;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.util.NetUtil;
import org.geysermc.connector.common.ping.GeyserPingInfo;
import org.geysermc.connector.GeyserConnector;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.TimeUnit;

public class GeyserLegacyPingPassthrough implements IGeyserPingPassthrough, Runnable {
    private static final byte[] HAPROXY_BINARY_PREFIX = new byte[]{13, 10, 13, 10, 0, 13, 10, 81, 85, 73, 84, 10};

    private final GeyserConnector connector;

    public GeyserLegacyPingPassthrough(GeyserConnector connector) {
        this.connector = connector;
    }

    private GeyserPingInfo pingInfo;

    /**
     * Start legacy ping passthrough thread
     * @param connector GeyserConnector
     * @return GeyserPingPassthrough, or null if not initialized
     */
    public static IGeyserPingPassthrough init(GeyserConnector connector) {
        if (connector.getConfig().isPassthroughMotd() || connector.getConfig().isPassthroughPlayerCounts()) {
            GeyserLegacyPingPassthrough pingPassthrough = new GeyserLegacyPingPassthrough(connector);
            // Ensure delay is not zero
            int interval = (connector.getConfig().getPingPassthroughInterval() == 0) ? 1 : connector.getConfig().getPingPassthroughInterval();
            connector.getLogger().debug("Scheduling ping passthrough at an interval of " + interval + " second(s).");
            connector.getGeneralThreadPool().scheduleAtFixedRate(pingPassthrough, 1, interval, TimeUnit.SECONDS);
            return pingPassthrough;
        }
        return null;
    }

    @Override
    public GeyserPingInfo getPingInformation(InetSocketAddress inetSocketAddress) {
        return pingInfo;
    }

    @Override
    public void run() {
        try (Socket socket = new Socket()) {
            String address = connector.getConfig().getRemote().getAddress();
            int port = connector.getConfig().getRemote().getPort();
            socket.connect(new InetSocketAddress(address, port), 5000);

            ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
            try (DataOutputStream handshake = new DataOutputStream(byteArrayStream)) {
                handshake.write(0x0);
                VarInts.writeUnsignedInt(handshake, MinecraftConstants.PROTOCOL_VERSION);
                VarInts.writeUnsignedInt(handshake, address.length());
                handshake.writeBytes(address);
                handshake.writeShort(port);
                VarInts.writeUnsignedInt(handshake, 1);
            }

            byte[] buffer;

            try (DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream())) {
                if (connector.getConfig().getRemote().isUseProxyProtocol()) {
                    // HAProxy support
                    // Based on https://github.com/netty/netty/blob/d8ad931488f6b942dabe28ecd6c399b4438da0a8/codec-haproxy/src/main/java/io/netty/handler/codec/haproxy/HAProxyMessageEncoder.java#L78
                    dataOutputStream.write(HAPROXY_BINARY_PREFIX);
                    dataOutputStream.writeByte((0x02 << 4) | HAProxyCommand.PROXY.byteValue());
                    dataOutputStream.writeByte(socket.getLocalAddress() instanceof Inet4Address ?
                            HAProxyProxiedProtocol.TCP4.byteValue() : HAProxyProxiedProtocol.TCP6.byteValue());
                    byte[] srcAddrBytes = NetUtil.createByteArrayFromIpAddressString(
                            ((InetSocketAddress) socket.getLocalSocketAddress()).getAddress().getHostAddress());
                    byte[] dstAddrBytes = NetUtil.createByteArrayFromIpAddressString(address);
                    dataOutputStream.writeShort(srcAddrBytes.length + dstAddrBytes.length + 4);
                    dataOutputStream.write(srcAddrBytes);
                    dataOutputStream.write(dstAddrBytes);
                    dataOutputStream.writeShort(((InetSocketAddress) socket.getLocalSocketAddress()).getPort());
                    dataOutputStream.writeShort(port);
                }

                VarInts.writeUnsignedInt(dataOutputStream, byteArrayStream.size());
                dataOutputStream.write(byteArrayStream.toByteArray());
                dataOutputStream.writeByte(0x01);
                dataOutputStream.writeByte(0x00);

                try (DataInputStream dataInputStream = new DataInputStream(socket.getInputStream())) {
                    VarInts.readUnsignedInt(dataInputStream);
                    VarInts.readUnsignedInt(dataInputStream);
                    int length = VarInts.readUnsignedInt(dataInputStream);
                    buffer = new byte[length];
                    dataInputStream.readFully(buffer);
                    dataOutputStream.writeByte(0x09);
                    dataOutputStream.writeByte(0x01);
                    dataOutputStream.writeLong(System.currentTimeMillis());

                    VarInts.readUnsignedInt(dataInputStream);
                }
            }
            String json = new String(buffer);

            this.pingInfo = GeyserConnector.JSON_MAPPER.readValue(json, GeyserPingInfo.class);
        } catch (SocketTimeoutException | ConnectException ex) {
            this.pingInfo = null;
            this.connector.getLogger().debug("Connection timeout for ping passthrough.");
        } catch (JsonParseException | JsonMappingException ex) {
            this.connector.getLogger().error("Failed to parse json when pinging server!", ex);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
