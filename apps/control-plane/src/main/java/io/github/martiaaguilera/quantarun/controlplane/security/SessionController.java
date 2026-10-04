package io.github.martiaaguilera.quantarun.controlplane.security;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the presented credential belongs to. The console calls it to check a credential at sign-in and to show
 * whether it acts as the operator or as one project; it reveals nothing the caller does not already hold.
 */
@RestController
class SessionController {

    enum Role {
        OPERATOR,
        PROJECT
    }

    record Session(Role role, @Nullable UUID projectId) {}

    @GetMapping("/api/v1/session")
    Session session(Caller caller) {
        return switch (caller) {
            case Caller.Admin _ -> new Session(Role.OPERATOR, null);
            case Caller.ProjectMember member -> new Session(Role.PROJECT, member.projectId());
        };
    }
}
