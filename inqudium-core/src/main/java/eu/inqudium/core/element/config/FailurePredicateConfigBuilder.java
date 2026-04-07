package eu.inqudium.core.element.config;

import eu.inqudium.core.config.ExtensionBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/**
 * The evaluation logic follows a hierarchical priority system (Veto Principle).
 *
 * <p><b>Logical Chain:</b><br>
 * {@code finalPredicate = !isForceIgnored && (isForceRecorded || (isBaseIncluded && !isBaseIgnored))}
 * </p>
 *
 * <p><b>Evaluation steps and implicit properties:</b></p>
 * <ul>
 * <li><b>1. FORCE IGNORE (Veto):</b> If any {@code alwaysIgnoreWhen} predicate matches, the result is
 * immediately {@code false}, overriding all other rules.</li>
 * <li><b>2. FORCE RECORD (Super-Pass):</b> If any {@code alwaysRecordWhen} matches (and no Veto is present),
 * the result is {@code true}.</li>
 * <li><b>3. STANDARD EVALUATION:</b>
 * <ul>
 * <li><b>a) Base Inclusion:</b> If no {@code recordExceptions} or {@code recordWhen} rules are defined,
 * it defaults to <b>TRUE</b> (records everything). Otherwise, it must match a record rule.</li>
 * <li><b>b) Base Exclusion:</b> If the exception matches {@code ignoreExceptions} or {@code ignoreWhen},
 * it is filtered out (unless step 2 applied).</li>
 * </ul>
 * </li>
 * </ul>
 *
 * <p><b>Implicit Default States:</b></p>
 * <ul>
 * <li>Empty Record Rules:   Defaults to <b>TRUE</b> (Inclusion-first approach).</li>
 * <li>Empty Ignore Rules:   Defaults to <b>FALSE</b> (Nothing is filtered out).</li>
 * <li>Empty Force Rules:    Neutral (They do not influence the standard evaluation).</li>
 * </ul>
 */
public class FailurePredicateConfigBuilder extends ExtensionBuilder<FailurePredicateConfig> {
  private final List<Class<? extends Throwable>> recordExceptions = new ArrayList<>();
  private final List<Class<? extends Throwable>> ignoreExceptions = new ArrayList<>();
  private final List<Predicate<Throwable>> recordExceptionPredicates = new ArrayList<>();
  private final List<Predicate<Throwable>> ignoreExceptionPredicates = new ArrayList<>();
  private final List<Predicate<Throwable>> recordOverridePredicates = new ArrayList<>();
  private final List<Predicate<Throwable>> ignoreOverridePredicates = new ArrayList<>();

  FailurePredicateConfigBuilder() {
  }

  public static FailurePredicateConfigBuilder failurePredicate() {
    return new FailurePredicateConfigBuilder();
  }

  /**
   * Adds exception classes to the base inclusion list (White-List).
   * If any record rule is set, only matching exceptions are recorded by default.
   */
  @SafeVarargs
  public final FailurePredicateConfigBuilder recordExceptions(Class<? extends Throwable>... exceptions) {
    this.recordExceptions.addAll(Arrays.asList(exceptions));
    return this;
  }

  /**
   * Adds exception classes to the base exclusion list (Black-List).
   * Exceptions matching these classes will be ignored unless overridden by a force-record rule.
   */
  @SafeVarargs
  public final FailurePredicateConfigBuilder ignoreExceptions(Class<? extends Throwable>... exceptions) {
    this.ignoreExceptions.addAll(Arrays.asList(exceptions));
    return this;
  }

  /**
   * Defines an absolute Veto rule.
   * Any exception matching this predicate will ALWAYS be ignored, regardless of any other rules.
   */
  public FailurePredicateConfigBuilder alwaysIgnoreWhen(Predicate<Throwable> predicate) {
    this.ignoreOverridePredicates.add(predicate);
    return this;
  }

  /**
   * Defines a high-priority record rule.
   * Any exception matching this predicate will be recorded, even if it is on the ignore list,
   * provided no force-ignore rule applies.
   */
  public FailurePredicateConfigBuilder alwaysRecordWhen(Predicate<Throwable> predicate) {
    this.recordOverridePredicates.add(predicate);
    return this;
  }

  /**
   * Adds a custom predicate to the base exclusion list.
   * Matches will be ignored during standard evaluation.
   */
  public FailurePredicateConfigBuilder ignoreWhen(Predicate<Throwable> predicate) {
    this.ignoreExceptionPredicates.add(predicate);
    return this;
  }

  /**
   * Adds a custom predicate to the base inclusion list.
   * If set, only exceptions matching this (or other record rules) will be considered for recording.
   */
  public FailurePredicateConfigBuilder recordWhen(Predicate<Throwable> predicate) {
    this.recordExceptionPredicates.add(predicate);
    return this;
  }

  @Override
  public FailurePredicateConfig build() {
    // 1. Base Inclusion
    // Check if any specific record rules exist. If none exist, we default to recording everything.
    boolean hasBaseRecordRules = !recordExceptions.isEmpty() || !recordExceptionPredicates.isEmpty();

    Predicate<Throwable> isBaseIncluded = t -> {
      if (!hasBaseRecordRules) {
        return true;
      }
      boolean matchesClass = recordExceptions.stream()
          .anyMatch(exClass -> exClass.isAssignableFrom(t.getClass()));
      boolean matchesPredicate = recordExceptionPredicates.stream()
          .anyMatch(p -> p.test(t));
      return matchesClass || matchesPredicate;
    };

    // 2. Base Exclusion
    Predicate<Throwable> isBaseIgnored = t -> {
      boolean matchesClass = ignoreExceptions.stream()
          .anyMatch(exClass -> exClass.isAssignableFrom(t.getClass()));
      boolean matchesPredicate = ignoreExceptionPredicates.stream()
          .anyMatch(p -> p.test(t));
      return matchesClass || matchesPredicate;
    };

    // 3. Overrides (Super Rules)
    Predicate<Throwable> isForceRecorded = t -> recordOverridePredicates.stream()
        .anyMatch(p -> p.test(t));

    Predicate<Throwable> isForceIgnored = t -> ignoreOverridePredicates.stream()
        .anyMatch(p -> p.test(t));

    // 4. Combine all rules in order of priority:
    // - isForceIgnored acts as an absolute veto (negated, meaning "must NOT be force ignored").
    // - If not force ignored, it can either be force recorded OR pass the standard evaluation.
    // - Standard evaluation: Must be included by base rules AND NOT ignored by base rules.

    Predicate<Throwable> standardEvaluation = isBaseIncluded.and(isBaseIgnored.negate());

    Predicate<Throwable> finalPredicate = isForceIgnored.negate()
        .and(isForceRecorded.or(standardEvaluation));

    return new FailurePredicateConfig(finalPredicate);
  }
}
