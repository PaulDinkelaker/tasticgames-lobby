package de.tasticgames.lobby.npc;

/**
 * What a lobby service NPC does when a player clicks it (left and right click behave the same).
 */
public enum LobbyNpcAction {

    /** Opens the TasticPass overview dialog. */
    PASS,

    /** Requests a routed transfer to the configured {@code target} server type. */
    TRANSFER,

    /** Opens the Cookie Clicker menu. */
    COOKIE,

    /** Sends an info message only – the NPC is decoration with a name. */
    NONE
}
