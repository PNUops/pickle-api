package kr.ac.pusan.pickle.gpu;

/** Evidence can suggest a review; it never authorizes device changes. */
public interface GpuReclaimPolicy {
    boolean shouldReview(GpuUtilizationPolicy.Assessment assessment);
}
