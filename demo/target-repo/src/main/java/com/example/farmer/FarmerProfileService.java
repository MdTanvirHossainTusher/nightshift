package com.example.farmer;

/**
 * Seeded defect #6 — the control case. A concurrent update throws
 * OptimisticLockException, and the caller already retries it correctly with a
 * bounded attempt count.
 *
 * <p>The log therefore shows a recurring WARN that looks alarming and is in fact
 * working as designed. A correct triage recognises this and does NOT open a pull
 * request: the right output is a MINOR incident with "already handled" in the
 * rationale. An agent that patches this is producing review noise, which is the
 * failure mode this case is here to catch.
 */
public class FarmerProfileService {

    private static final int MAX_ATTEMPTS = 3;

    private final ProfileRepository repository;

    public FarmerProfileService(ProfileRepository repository) {
        this.repository = repository;
    }

    public void rename(long farmerId, String newName) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                repository.updateName(farmerId, newName); // NS_FRAME_OPTLOCK
                return;
            } catch (OptimisticLockException ex) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ex;
                }
                // Bounded retry. This is the correct handling.
            }
        }
    }

    public interface ProfileRepository {
        void updateName(long farmerId, String newName) throws OptimisticLockException;
    }

    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }
}
