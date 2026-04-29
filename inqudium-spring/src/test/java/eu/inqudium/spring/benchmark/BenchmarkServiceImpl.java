package eu.inqudium.spring.benchmark;

/**
 * Trivial implementation of {@link BenchmarkService}. Annotated with
 * {@link Benchmarked} so the aspect pointcuts match.
 *
 * <p>The method body is deliberately minimal — one arithmetic op — so that
 * the benchmark measures aspect overhead, not target-method work.</p>
 */
public class BenchmarkServiceImpl implements BenchmarkService {

    @Override
    @Benchmarked
    public int execute(int input) {
        return input + 1;
    }
}
