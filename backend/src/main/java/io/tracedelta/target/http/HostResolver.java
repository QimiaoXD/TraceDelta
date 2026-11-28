package io.tracedelta.target.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolveAll(String host) throws UnknownHostException;
}
