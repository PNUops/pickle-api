-- Rename the seeded terms/privacy display titles to the official product name
-- (부산대학교 클라우드 플랫폼). This is a display-wording fix, not a document
-- revision: the v1 rows are updated in place (same id/version) so existing
-- user_consents stay valid and no re-consent gate is triggered. Inserting a
-- v2 row here would immediately flip every consented user back to the consent
-- gate, which a title spelling change does not warrant. The 제1조 "피클" alias
-- definition is kept intact on purpose (official-name + alias wording).

update terms_versions
set title = '부산대학교 클라우드 플랫폼 서비스 이용약관',
    body  = replace(body, '# 피클(Pickle) 서비스 이용약관',
                          '# 부산대학교 클라우드 플랫폼 서비스 이용약관')
where doc_type = 'TERMS_OF_SERVICE' and version = 1;

update terms_versions
set body = replace(body, '# 피클(Pickle) 개인정보처리방침',
                         '# 부산대학교 클라우드 플랫폼 개인정보처리방침')
where doc_type = 'PRIVACY_POLICY' and version = 1;
