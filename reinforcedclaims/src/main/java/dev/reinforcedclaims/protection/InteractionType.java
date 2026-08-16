package dev.reinforcedclaims.protection;

// The permission categories an AccessGrant is checked against.
public enum InteractionType {
    PLACE_BREAK,
    CONTAINER,
    DOOR,
    REDSTONE,
    ENDERCHEST,
    OTHER_INTERACT,
    // Edit the grant list, and reinforce or overlap inside a claim. Never produced by an interaction.
    MODIFY_PERMISSIONS
}
