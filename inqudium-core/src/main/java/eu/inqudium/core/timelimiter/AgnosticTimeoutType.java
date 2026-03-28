package eu.inqudium.core.timelimiter;

/**
 * Agnostic HTTP timeout configuration types (ADR-012).
 *
 * <p>Abstracts the timeout parameters of various JVM HTTP clients (Apache HttpClient,
 * OkHttp, Java 11+ HttpClient, Spring WebClient) into a unified, client-independent set.
 * Each constant represents a distinct phase of the HTTP call lifecycle.
 *
 * <h2>Mapping to common clients</h2>
 * <table border="1" cellpadding="5" cellspacing="0">
 * <tr><th>Agnostic type</th><th>Apache v5</th><th>OkHttp</th><th>Java 11+ HttpClient</th><th>Spring WebClient</th></tr>
 * <tr><td>{@link #CONNECTION_ACQUIRE}</td><td>ConnectionRequestTimeout</td><td>implicit via callTimeout</td><td>implicit via timeout</td><td>pendingAcquireTimeout</td></tr>
 * <tr><td>{@link #CONNECTION_ESTABLISHMENT}</td><td>ConnectTimeout</td><td>connectTimeout</td><td>connectTimeout</td><td>CONNECT_TIMEOUT_MILLIS</td></tr>
 * <tr><td>{@link #READ_INACTIVITY}</td><td>ResponseTimeout</td><td>readTimeout</td><td>implicit via timeout</td><td>ReadTimeoutHandler</td></tr>
 * <tr><td>{@link #WRITE_OPERATION}</td><td>n/a (OS-level)</td><td>writeTimeout</td><td>implicit via timeout</td><td>WriteTimeoutHandler</td></tr>
 * <tr><td>{@link #SERVER_RESPONSE}</td><td>n/a</td><td>n/a</td><td>n/a</td><td>responseTimeout</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
public enum AgnosticTimeoutType {

  /**
   * Maximum time waiting for a free TCP connection from an internal pool.
   *
   * <p>Relevant for connection-pooling clients. Without an explicit limit, a saturated
   * pool can cause indefinite blocking of the calling thread.
   */
  CONNECTION_ACQUIRE,

  /**
   * Maximum time to complete the TCP handshake (and TLS negotiation if applicable).
   *
   * <p>This is the first network-I/O timeout in the call lifecycle. Short values
   * surface unreachable hosts quickly; excessively short values cause false failures
   * on high-latency links.
   */
  CONNECTION_ESTABLISHMENT,

  /**
   * Maximum inactivity time between two consecutively received data packets.
   *
   * <p>Triggers if the server stops sending data mid-response. Does not bound
   * the total transfer time — only the gap between individual reads.
   */
  READ_INACTIVITY,

  /**
   * Maximum time a single write operation to the network socket is allowed to block.
   *
   * <p>Protects against stalled sockets during request upload. Not natively
   * supported by all clients (e.g. Apache HttpClient v5 relies on OS-level limits).
   */
  WRITE_OPERATION,

  /**
   * Maximum time waited for the server to start responding (Time To First Byte, TTFB)
   * after the request has been fully sent.
   *
   * <p>Distinct from {@link #READ_INACTIVITY}: TTFB covers the gap between the last
   * sent request byte and the first received response byte, whereas read-inactivity
   * measures subsequent gaps between data packets mid-stream.
   *
   * <p>Primarily exposed by Spring WebClient via {@code HttpClient.responseTimeout()}.
   */
  SERVER_RESPONSE
}
