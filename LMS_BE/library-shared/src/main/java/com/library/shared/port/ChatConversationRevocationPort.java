package com.library.shared.port;

/** Invalidates short-lived chat contexts after authenticated logout. */
public interface ChatConversationRevocationPort { void revoke(Long userId); }
