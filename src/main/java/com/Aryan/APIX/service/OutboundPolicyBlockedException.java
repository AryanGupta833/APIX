package com.Aryan.APIX.service;

/** Marker used to distinguish a policy denial from ordinary network and HTTP failures. */
public final class OutboundPolicyBlockedException extends RuntimeException {
    public OutboundPolicyBlockedException() { super("Outbound target blocked by APiX network policy"); }
}
