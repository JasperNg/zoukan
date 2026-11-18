// ZOUKAN -->
package eu.kanade.tachiyomi.network

/**
 * Whether DNS over HTTPS leaves local-only names (`localhost`, `nas`, `nas.local`) to the system's
 * DNS and the Komga/Kavita fallback covers only those names (see [isLocalOnlyHostName]), and
 * whether the system proxy's host name goes to the system's DNS with the DoH queries on their own
 * threads (see [desktopDohDns]). True on desktop; false on Android, which keeps Komikku's behavior.
 */
internal expect val DOH_LOCAL_NAMES_USE_SYSTEM_DNS: Boolean
// ZOUKAN <--
