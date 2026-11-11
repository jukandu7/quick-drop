package com.example.quickdrop.network;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.*;
import java.util.*;

@Service
public class NetworkAddressService {
    @Value("${quickdrop.network-address:}")
    private volatile String selectedAddress = "";

    public String selectedAddress() { return selectedAddress; }

    public void selectAddress(String address) {
        String selected = address == null ? "" : address.trim();
        if (!selected.isEmpty() && availableAddresses().stream().noneMatch(a -> a.address().equals(selected)))
            throw new IllegalArgumentException("That address is no longer available. Choose an active network address.");
        selectedAddress = selected;
    }

    public String findLocalIpv4Address() {
        List<AddressChoice> choices = availableAddresses();
        String selected = selectedAddress;
        if (!selected.isBlank()) {
            // Never replace an explicit LAN selection with a VPN address silently.
            return choices.stream().filter(a -> a.address().equals(selected)).map(AddressChoice::address)
                    .findFirst().orElse(null);
        }
        return choices.stream().map(AddressChoice::address).findFirst().orElse(null);
    }

    public List<AddressChoice> availableAddresses() {
        List<AddressChoice> choices = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface adapter = interfaces.nextElement();
                if (!adapter.isUp() || adapter.isLoopback()) continue;
                for (InetAddress address : Collections.list(adapter.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isAnyLocalAddress()
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        choices.add(new AddressChoice(address.getHostAddress(), adapter.getName(),
                                adapter.getDisplayName(), adapter.isVirtual(), address.isSiteLocalAddress()));
                    }
                }
            }
        } catch (SocketException ignored) {
            return List.of();
        }
        return choices.stream().sorted(Comparator.comparing(AddressChoice::virtual)
                .thenComparing(a -> !a.privateAddress()).thenComparing(AddressChoice::interfaceName)
                .thenComparing(AddressChoice::address)).toList();
    }

    public record AddressChoice(String address, String interfaceName, String displayName,
                                boolean virtual, boolean privateAddress) {
        public String label() {
            return address + " — " + displayName + " (" + interfaceName + ")" + (virtual ? " [virtual]" : "");
        }
    }
}
