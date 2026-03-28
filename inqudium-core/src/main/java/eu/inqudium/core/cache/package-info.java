/**
 * Cache configuration — placeholder for Phase 2.
 *
 * <p>The cache element stores successful results to reduce load and improve response
 * times. In the canonical pipeline order (ADR-017), the cache is the outermost element:
 * a cache hit returns immediately without invoking any other resilience element.
 *
 * <p>This package currently contains only {@code InqCacheConfig} as a configuration
 * skeleton. The behavioral contract and implementations will be specified in a
 * future ADR when the cache element enters active development.
 */
package eu.inqudium.core.cache;
