package eu.inqudium.core.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Parameterized Tests for all Wrapper Types including Function")
class FinalWrapperHierarchyTest {

  /**
   * Data source for all wrapper types.
   * Generates a "Bridge" for each type, making them uniformly testable.
   */
  static Stream<WrapperTestBridge> wrapperProvider() {
    return Stream.of(
        // --- Supplier Bridge ---
        new WrapperTestBridge() {
          private final SupplierWrapper<String> inner = new SupplierWrapper<>("Supplier-Inner", () -> "val");
          private final SupplierWrapper<String> root = new SupplierWrapper<>("Supplier-Root", inner);

          @Override
          public void executeRoot() {
            root.get();
          }

          @Override
          public void triggerInner() {
            inner.get();
          }

          @Override
          public BaseWrapper<?, ?, ?, ?> getWrapper() {
            return root;
          }

          @Override
          public String toString() {
            return "SupplierWrapper";
          }
        },

        // --- Runnable Bridge ---
        new WrapperTestBridge() {
          private final RunnableWrapper inner = new RunnableWrapper("Runnable-Inner", () -> {
          });
          private final RunnableWrapper root = new RunnableWrapper("Runnable-Root", inner);

          @Override
          public void executeRoot() {
            root.run();
          }

          @Override
          public void triggerInner() {
            inner.run();
          }

          @Override
          public BaseWrapper<?, ?, ?, ?> getWrapper() {
            return root;
          }

          @Override
          public String toString() {
            return "RunnableWrapper";
          }
        },

        // --- Callable Bridge ---
        new WrapperTestBridge() {
          private final CallableWrapper<String> inner = new CallableWrapper<>("Callable-Inner", () -> "val");
          private final CallableWrapper<String> root = new CallableWrapper<>("Callable-Root", inner);

          @Override
          public void executeRoot() throws Exception {
            root.call();
          }

          @Override
          public void triggerInner() throws Exception {
            inner.call();
          }

          @Override
          public BaseWrapper<?, ?, ?, ?> getWrapper() {
            return root;
          }

          @Override
          public String toString() {
            return "CallableWrapper";
          }
        },

        // --- Function Bridge ---
        new WrapperTestBridge() {
          private final FunctionWrapper<String, String> inner = new FunctionWrapper<>("Function-Inner", (arg) -> arg + "-processed");
          private final FunctionWrapper<String, String> root = new FunctionWrapper<>("Function-Root", inner);

          @Override
          public void executeRoot() {
            root.apply("Test-Input");
          }

          @Override
          public void triggerInner() {
            inner.apply("Test-Input");
          }

          @Override
          public BaseWrapper<?, ?, ?, ?> getWrapper() {
            return root;
          }

          @Override
          public String toString() {
            return "FunctionWrapper";
          }
        }
    );
  }

  // Helper interface to unify the execution of different functional types
  interface WrapperTestBridge {
    void executeRoot() throws Exception;

    void triggerInner() throws Exception;

    BaseWrapper<?, ?, ?, ?> getWrapper();
  }

  @Nested
  @DisplayName("Generic Structural Tests")
  class StructuralTests {

    @ParameterizedTest(name = "Testing hierarchy logic for {0}")
    @MethodSource("eu.inqudium.core.pipeline.FinalWrapperHierarchyTest#wrapperProvider")
    @DisplayName("Every wrapper type must correctly represent the hierarchy string without a leading symbol at root")
    void hierarchyStringMustBeCorrect(WrapperTestBridge bridge) {
      // Given
      BaseWrapper<?, ?, ?, ?> wrapper = bridge.getWrapper();

      // When
      String hierarchy = wrapper.toStringHierarchy();
      String[] lines = hierarchy.split("\n");

      // Then
      // Expected format:
      // Line 0: Chain-ID: ...
      // Line 1: <Name>-Root (Without └──)
      // Line 2:   └── <Name>-Inner
      assertThat(lines[1])
          .as("Root layer should not have a leading tree symbol")
          .doesNotContain("└──")
          .contains("-Root");

      assertThat(lines[2])
          .as("Inner layer must be indented and have a tree symbol")
          .contains("  └── ")
          .contains("-Inner");
    }

    @ParameterizedTest(name = "Testing identity for {0}")
    @MethodSource("eu.inqudium.core.pipeline.FinalWrapperHierarchyTest#wrapperProvider")
    @DisplayName("The Chain-ID must be stable and shared between layers")
    void chainIdMustBeStable(WrapperTestBridge bridge) {
      // Given
      BaseWrapper<?, ?, ?, ?> root = bridge.getWrapper();
      BaseWrapper<?, ?, ?, ?> inner = root.inner();

      // When & Then
      assertThat(root.chainId())
          .as("Chain-ID must be non-null and identical across the hierarchy")
          .isNotNull()
          .isEqualTo(inner.chainId());
    }
  }
}