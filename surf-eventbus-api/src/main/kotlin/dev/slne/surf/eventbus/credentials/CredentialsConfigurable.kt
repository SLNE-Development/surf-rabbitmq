package dev.slne.surf.eventbus.credentials

/**
 * Resolved settings a [Credentials] can be read out of.
 *
 * Implemented by the per-transport settings types, so the provider seam can be stated once
 * over both rather than twice with one shape each.
 */
interface CredentialsConfigurable
