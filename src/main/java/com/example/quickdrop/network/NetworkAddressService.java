package com.example.quickdrop.network;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

@Service
public class NetworkAddressService {

    public String findLocalIpv4Address() {
        String routedAddress = findDefaultRouteAddress();

        if (routedAddress != null) {
            return routedAddress;
        }

        String interfaceAddress = findInterfaceAddress();

        if (interfaceAddress != null) {
            return interfaceAddress;
        }

        return "127.0.0.1";
    }

    private String findDefaultRouteAddress() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            InetAddress address = socket.getLocalAddress();

            if (isUsable(address)) {
                return address.getHostAddress();
            }
        } catch (IOException ignored) {
            // Fall back to inspecting the active network interfaces.
        }

        return null;
    }

    private String findInterfaceAddress() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface =
                        interfaces.nextElement();

                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()
                        || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (isUsable(address)) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // The loopback address below is the safe final fallback.
        }

        return null;
    }

    private boolean isUsable(InetAddress address) {
        return address instanceof Inet4Address
                && address.isSiteLocalAddress()
                && !address.isLoopbackAddress();
    }
}
