-- Security-review follow-up to the V55 expansion (2026-07-28 independent
-- review of self-service publishing): with the approval gate gone, the
-- reserved list is the squatting defense — add campus auth/identity service
-- names the survey missed (plato, sugang, haksa), generic authentication
-- labels (auth, oauth, signin, otp, ...), the bare university tokens, and the
-- obvious numbered/hyphenated composites of already-reserved names. Reserved
-- matching stays exact (word-level matching was considered and rejected: it
-- would block legitimate compounds like my-blog). 416 entries.

update settings set value = '
    [
    "www","api","admin","ssh","mail","console","staging","abeek","about","abuse","accs","acme",
    "adminer","agent","aicms","aiedu","aisec","alpha","alumni","android","app","apps","archive",
    "arise-ai","artifactory","assets","athic","autoconfig","autodiscover","backup","backup2",
    "balancer","bbs","bce-nc","bce-space","beta","billing","biochemistry","blog","board","book",
    "booking","broadcast","bsclab","build","calendar","canvas","career","cart","casb","ccm",
    "ccrf","cdn","cea","cert","cesa","channelpnu","chat","checkout","class","clinic","clopy",
    "cloud","cloudpc","cloudpcuser","code","conference","confluence","contact","course","cpmd",
    "cse","csep","damunhwa","data","database","dcollection","demo","deploy","dev","develop",
    "development","dhcp","disk","dkim","dmarc","dns","dns1","dns2","doc","docker","docs","dorm",
    "dormitory","download","downloads","drive","dses2","dspace","ebook","eclass","ecoinfo",
    "edge","edu","edurium","eiiu","elastic","elearning","electron","eproxy","essay","faq",
    "file","files","firewall","form","forms","forum","ftp","ftps","gate","gateway","git",
    "github","gitlab","grafana","graphql","guide","gym","harbor","health","help","herald","him",
    "home","hospital","hostmaster","housing","hrd","hstc","iacuc","ibm","icert","idisk","image",
    "images","imap","img","inc","induk","info","infosec","internal","ios","itc","itop","itrc",
    "jenkins","jira","job","jobclub","jobs","journal","jupyter","k8s","kerberos","kibana",
    "kubernetes","lab","labcenter","labs","labs-safety","las","law","lawlib","ldap","lecture",
    "legacy","legal","lend","lib","lib2","libguides","libl","libm","libn","libp","library",
    "libs","live","lms","localhost","localinno","log","login","logs","loveme","lproxy","m365",
    "manual","map","maps","mda","mdorm","media","medical","medlib","meet","melib","metrics",
    "mirror","mobile","mof-db","mongo","monitor","monitoring","moodle","mssql","mta","mta-sts",
    "museum","music","mx1","mx2","mypage","mysql","nagios","nanolib","nas","neoespa","new",
    "news","next","nexus","nihon","no-reply","noreply","ns1","ns2","ns3","ns4","nsslab",
    "nsthel","ntp","o365","oer","official","old","oldlabcenter","onestop","open","openshift",
    "opus","oracle","order","origin","palito","pass","pay","payment","pbis","photo","photos",
    "phpmyadmin","phys","picee","pickle","pickle-official","pims","pip","pipeline","placement",
    "plms","pnu-onlinesign","pnucse","pnuyh-rcb","policy","poll","pop","pop3","portal",
    "postgres","postgresql","postmaster","ppes","press","preview","print","printer","privacy",
    "private","prod","production","prof","prometheus","proxy","public","pulip","radio","radius",
    "rancher","rdp","reading","recruit","redis","redmine","registry","relay","release","repo",
    "repository","research","resolver","rmf","root","router","rppg","runner","sandbox","sanhak",
    "sanhak-erp","sanhakdb","sce","schedule","sci","search","seat","sec","security",
    "sedu-support","sftp","shop","sitemap","smtp","smtp2","soc","space","speed","sqm2022",
    "sshgw","sso","ssspa","stage","static","status","storage","store","stream","study",
    "support","survey","svn","swagger","swcss","sys","syslog","system","telnet","temp",
    "terminal","terms","test","testing","tftp","tmp","uat","umypage","upload","uploads",
    "uptime","uwcms","vialab","video","vote","vpn","waf","wcms","web","webmail","webmaster",
    "weekly","wellknown","wetech","whois","wiki","www2","ydorm","zabbix","account","accounts",
    "auth","cas","email","haksa","idp","login2","mail2","mfa","oauth","otp","password","plato",
    "pnu","pnu-login","pnu-sso","portal2","pusan","reset","saml","secure","signin","signup",
    "sso-login","sugang","verify","webmail2"
    ]
'::jsonb where key = 'reserved_subdomains';
