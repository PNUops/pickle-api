package kr.ac.pusan.pickle.vm;

/**
 * The four power intents an administrator may place on a VM (contract enum
 * {@code VmPowerAction}). Each maps to one worker in {@code VmPowerJobs} and to
 * one allowed set of source states; the names double as the pending-action
 * label the claim column carries.
 */
public enum VmPowerAction {
    START,
    SHUTDOWN,
    REBOOT,
    FORCE_STOP
}
