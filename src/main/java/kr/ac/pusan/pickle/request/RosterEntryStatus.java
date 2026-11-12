package kr.ac.pusan.pickle.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where one 학번 of a pasted roster stands in a workspace, before anything is written. */
@Schema(description = """
        명단의 학번 한 건이 워크스페이스에서 어떤 상태인지 나타내요.
        MEMBER: 해당 학번의 활성 계정이 이미 구성원이에요.
        REGISTERED: 해당 학번의 활성 계정이 있지만 구성원이 아니에요. 대상자로 신청하면 구성원으로 추가해요.
        INVITED: 해당 학번으로 대기 중인 초대가 있어요.
        NEW: 활성 계정도 대기 중인 초대도 없어요. 대상자로 신청하면 초대를 만들어요.
        INVALID: 학번 형식이 아니에요.
        DUPLICATE: 같은 학번이 앞에 이미 있어요.""")
public enum RosterEntryStatus {
    MEMBER,
    REGISTERED,
    INVITED,
    NEW,
    INVALID,
    DUPLICATE
}
