package eu.inqudium.aspect.pipeline.example;

/**
 * Example service class whose methods are intercepted by the
 * {@link PipelinedAspect}.
 *
 * <p>In a real application, this would be a repository, a remote client,
 * or any business-critical component that benefits from cross-cutting
 * authorization, logging, and timing.</p>
 */
public class GreetingService {

    /**
     * Returns a personalized greeting.
     *
     * <p>When the {@link PipelinedAspect} is active, this method is wrapped
     * in a three-layer pipeline: AUTHORIZATION → LOGGING → TIMING.</p>
     *
     * @param name the name to greet
     * @return a greeting string
     */
    @Pipelined
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
