package io.github.martiaaguilera.quantarun.controlplane.security;

import java.util.UUID;

/**
 * The authenticated identity behind an API request. Controllers receive it as a method argument; there is no
 * ambient security context to forget to check.
 */
public sealed interface Caller {

    /** Operator with the admin token: may manage projects and read every project's data. */
    record Admin() implements Caller {}

    /** Holder of a project API key: confined to that project's data. */
    record ProjectMember(UUID projectId) implements Caller {}

    default boolean canAccessProject(UUID projectId) {
        return switch (this) {
            case Admin _ -> true;
            case ProjectMember member -> member.projectId().equals(projectId);
        };
    }

    default void requireAdmin() {
        if (!(this instanceof Admin)) {
            throw new ForbiddenException("This operation requires the admin token.");
        }
    }

    default UUID requireProjectMember() {
        if (this instanceof ProjectMember member) {
            return member.projectId();
        }
        throw new ForbiddenException("This operation requires a project API key.");
    }
}
