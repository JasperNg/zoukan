// ZOUKAN: whole file -->
package xyz.nulldev.androidcompat.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.function.Supplier;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * What an extension's own {@code new OkHttpClient()} and {@code new OkHttpClient.Builder()}
 * become. BytecodeEditor rewrites those constructor calls in extension classes into calls of
 * {@link #newClient()} and {@link #newBuilder()}, which give the same fresh client (no app
 * interceptors, cookies or cache) except that names resolve through the app's DNS: over HTTPS when
 * the user turned it on, where a client of the extension's own would send every host name to the
 * system's DNS.
 */
public final class ExtensionHttp {
    private ExtensionHttp() {}

    private static volatile Supplier<Dns> dnsProvider = null;

    /**
     * The DNS every client made here uses. It asks the provider set by the app on each lookup, so a
     * client an extension makes before the app's network is set up still gets the app's DNS.
     */
    public static final Dns DNS = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            return current().lookup(hostname);
        }

        @Override
        public String toString() {
            return "ExtensionHttp.DNS";
        }
    };

    /** Set by the app at startup: its client's DNS ({@code NetworkHelper.client.dns}). Null for the system's. */
    public static void setDnsProvider(Supplier<Dns> provider) {
        dnsProvider = provider;
    }

    /** The DNS lookups go to now: the app's, or the system's when the app set none. */
    public static Dns current() {
        Supplier<Dns> provider = dnsProvider;
        Dns dns = provider == null ? null : provider.get();
        return dns == null ? Dns.SYSTEM : dns;
    }

    /** Replaces {@code new OkHttpClient()} in extension code. */
    public static OkHttpClient newClient() {
        return newBuilder().build();
    }

    /** Replaces {@code new OkHttpClient.Builder()} in extension code. */
    public static OkHttpClient.Builder newBuilder() {
        return new OkHttpClient.Builder().dns(DNS);
    }
}
// ZOUKAN: whole file <--
