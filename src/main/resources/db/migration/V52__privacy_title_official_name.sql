-- Align the privacy-policy display title with the terms document (V51):
-- prefix the official product name. Same in-place v1 UPDATE rationale as V51 —
-- a display-wording fix, not a document revision, so existing consents stay
-- valid and no re-consent gate fires.

update terms_versions
set title = '부산대학교 클라우드 플랫폼 개인정보처리방침'
where doc_type = 'PRIVACY_POLICY' and version = 1;
