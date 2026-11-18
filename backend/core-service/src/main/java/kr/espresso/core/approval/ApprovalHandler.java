package kr.espresso.core.approval;

import java.util.Map;
import java.util.UUID;

/** 결재 대상 문서별 처리기 (물류·결손·급여·비용 …) */
public interface ApprovalHandler {
    String docType();
    String docLabel();
    /** 결재함 표시용: no, title, storeId, amount, status, link */
    Map<String, Object> summary(UUID docId);
    default void onStepApproved(UUID docId, String role, UUID actor) {}
    void onApproved(UUID docId, UUID actor);
    void onRejected(UUID docId, UUID actor, String comment);
    default void onWithdrawn(UUID docId, UUID actor) {}
}
