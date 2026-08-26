package kr.ac.pusan.pickle.notice;

/**
 * One image ready to be written to a response: the stored bytes, the type they
 * were stored under, and how far they may be cached.
 *
 * <p>Separate from {@link NoticeImageContent} because the two answer different
 * questions. That one is the storage seam's vocabulary — what is held, and in
 * what format — and a store cannot know who is allowed to see it. This one is
 * the serving decision, which depends entirely on the notice the image hangs
 * off and on who asked.</p>
 *
 * @param sharedCacheable whether a cache shared between users may keep this
 *     response. True only when an anonymous request for the same URL would
 *     succeed right now, which is the exact condition under which handing the
 *     bytes to a later, different requester is correct.
 */
public record NoticeImageDelivery(String contentType, byte[] bytes, boolean sharedCacheable) {
}
