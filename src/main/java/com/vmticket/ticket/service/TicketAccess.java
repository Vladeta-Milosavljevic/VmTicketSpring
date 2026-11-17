package com.vmticket.ticket.service;

import java.util.Set;

import org.springframework.security.access.AccessDeniedException;

import com.vmticket.common.error.TicketNotFoundException;
import com.vmticket.ticket.entity.Ticket;
import com.vmticket.user.Usernames;
import com.vmticket.user.entity.Role;
import com.vmticket.user.entity.User;

public final class TicketAccess {
    private TicketAccess() {
    }

    public static void requireCanRead(Ticket ticket, String username, Set<Role> roles) {
        if (canRead(ticket, username, roles)) {
            return;
        }
        throw new TicketNotFoundException("Ticket not found: " + ticket.getId());
    }

    public static boolean canRead(Ticket ticket, String username, Set<Role> roles) {
        if (isAdmin(roles)) {
            return true;
        }
        String caller = Usernames.normalize(username);
        if (sameUser(ticket.getRequester(), caller)) {
            return true;
        }
        if (sameUser(ticket.getAgent(), caller) && hasRole(roles, Role.AGENT)) {
            return true;
        }
        if (sameUser(ticket.getManager(), caller) && hasRole(roles, Role.MANAGER)) {
            return true;
        }
        return false;
    }

    public static void requireCanWrite(Ticket ticket, String username, Set<Role> roles) {
        if (isAdmin(roles)) {
            return;
        }
        String caller = Usernames.normalize(username);
        if (sameUser(ticket.getAgent(), caller) && hasRole(roles, Role.AGENT)) {
            return;
        }
        if (sameUser(ticket.getManager(), caller) && hasRole(roles, Role.MANAGER)) {
            return;
        }
        throw new AccessDeniedException("Access denied");
    }

    private static boolean isAdmin(Set<Role> roles) {
        return hasRole(roles, Role.ADMIN);
    }

    private static boolean hasRole(Set<Role> roles, Role role) {
        return roles != null && roles.contains(role);
    }

    private static boolean sameUser(User user, String username) {
        return user != null && username != null && username.equals(Usernames.normalize(user.getUsername()));
    }
}
