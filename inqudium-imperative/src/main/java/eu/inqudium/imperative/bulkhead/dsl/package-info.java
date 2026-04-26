/**
 * Imperative-paradigm DSL implementation for bulkheads.
 *
 * <p>This package contains the concrete imperative bulkhead builder
 * ({@link eu.inqudium.imperative.bulkhead.dsl.DefaultImperativeBulkheadBuilder
 * DefaultImperativeBulkheadBuilder}). The paradigm-agnostic interfaces and the abstract base
 * implementation live in {@code eu.inqudium.config.dsl}; this module provides only the
 * concrete imperative shell, which the runtime instantiates per
 * {@code .bulkhead("name", b -> ...)} DSL invocation inside an {@code .imperative(...)}
 * section.
 */
package eu.inqudium.imperative.bulkhead.dsl;
