package com.mira.rtp.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class RtpTeleportEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location destination;
    private final int attempts;

    public RtpTeleportEvent(Player player, Location destination, int attempts) {
        this.player = player;
        this.destination = destination.clone();
        this.attempts = attempts;
    }

    public Player player() { return player; }
    public Location destination() { return destination.clone(); }
    public int attempts() { return attempts; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
