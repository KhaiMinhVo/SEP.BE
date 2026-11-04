"""Build document-led state-machine diagrams, not a reverse-engineered code model.

Run: python docs/diagrams/state-diagram/generate_states.py
Render: ../render.ps1 -DiagramDirectory <this directory> -PlantUmlJar <jar>
All states and transition assumptions are explained in README.md.
"""
from pathlib import Path
import json
import re
from html import escape

OUT = Path(__file__).resolve().parent
STYLE = '''@startuml
skinparam backgroundColor white
skinparam shadowing false
skinparam defaultFontName Arial
skinparam defaultFontSize 15
skinparam ArrowFontSize 13
skinparam stateFontSize 16
skinparam stateBackgroundColor #FFFFFF
skinparam stateBorderColor #404040
skinparam ArrowColor #505050
skinparam roundcorner 12
skinparam nodesep 55
skinparam ranksep 65
hide empty description
'''
DIAGRAMS = []

def add(key, title, basis, classification, body, summary, extended=False):
    # Keep report images focused on states and transitions. Explanations are
    # retained separately in the manifest and documentation, not drawn as boxes.
    notes = re.findall(r'^note[^\n]*\n(.*?)^end note\s*$', body, flags=re.M | re.S)
    diagram_body = re.sub(r'^note\b.*?^end note\s*\n?', '', body, flags=re.M | re.S)
    text = STYLE + f'title InfluencerMatch | {title}\n'
    text += diagram_body.strip() + '\n@enduml\n'
    (OUT / f'{key}.puml').write_text(text, encoding='utf-8')
    DIAGRAMS.append(dict(id=key,title=title,basis=basis,classification=classification,summary=summary,extended=extended,notes=[note.strip() for note in notes]))

add('01-state-campaign', 'Campaign Lifecycle', 'FE-02; UC17; Brand screens 9-11; FE-10.7',
    'SRS state names | SDD transition and closure rules proposed', r'''
[*] --> DRAFT : Brand.createCampaign / save draft
DRAFT : editBrief [valid input] / save changes
DRAFT : acceptAiBrief [Brand confirmed] / save confirmed fields
DRAFT --> ACTIVE : Brand.activate\n[required brief fields valid]
DRAFT --> CANCELLED : Brand.cancel / record reason
ACTIVE : requestDiscovery / preserve versioned M3
ACTIVE : requestRecommendation / create separate run
ACTIVE --> COMPLETED : Brand.complete\n[required closure information processed]
ACTIVE --> CANCELLED : Brand.cancel\n[open collaboration obligations resolved]
COMPLETED : entry / retain campaign history
CANCELLED : entry / retain records and cancellation reason
DRAFT --> ARCHIVED : Brand.archiveDraft
COMPLETED --> ARCHIVED : Brand.archive / retain references
CANCELLED --> ARCHIVED : Brand.archive / retain references
ARCHIVED --> [*]
note right of ARCHIVED
Archive is not deletion.
Archiving M3 snapshots does not
automatically archive the Campaign.
end note
''', 'Vòng đời Campaign: nháp, hoạt động, hoàn tất, hủy và lưu trữ; tách việc chạy AI khỏi trạng thái chiến dịch.')

add('02-state-relationship', 'Brand-Creator Relationship', 'FE-07.4; UC36; LI-19; LI-22',
    'Core SRS relationship states | SDD transition guards proposed', r'''
[*] --> SHORTLISTED : Brand.startTracking / create relationship
SHORTLISTED : updateNotesOrPriority / retain evidence unchanged
SHORTLISTED --> CONTACTED : Brand.recordContact\n[external contact actually occurred]
SHORTLISTED --> NOT_INTERESTED : Brand.recordDecision / record reason
CONTACTED : recordInteraction / update notes and last contact
CONTACTED --> COLLABORATING : Brand.startCollaboration\n[campaign active and terms recorded]\n/ create collaboration
CONTACTED --> NOT_INTERESTED : Brand.recordDecision / record reason
NOT_INTERESTED --> CONTACTED : Brand.resumeContact\n[campaign still permits collaboration]
COLLABORATING --> COMPLETED : collaborationCompleted\n/ retain outcome and review references
COLLABORATING --> NOT_INTERESTED : collaborationCancelled\n[obligations resolved] / retain history
COMPLETED --> [*]
note right of CONTACTED
The Brand records external interactions.
The system does not infer a reply,
acceptance or agreement automatically.
end note
note right of SHORTLISTED
This machine tracks one relationship
within one campaign, not ShortlistItem membership.
Removing a shortlist item does not
erase collaboration history or create M1.
end note
''', 'CRM cơ bản theo FE-07: SHORTLISTED → CONTACTED → COLLABORATING → COMPLETED; có nhánh không hợp tác.')

add('03-state-collaboration', 'Collaboration and Outcome', 'FE-08; UC39, UC41-44; FE-10; Registration section 4',
    'Core business lifecycle | State labels normalized for SDD; cancellation rules proposed', r'''
[*] --> IN_PROGRESS : Brand.startCollaboration\n[campaign active; creator selected; terms recorded]
IN_PROGRESS : recordPublishedContent / retain available URL
IN_PROGRESS : recordOutcome / retain actual available results
IN_PROGRESS --> COMPLETED : Brand.completeCollaboration\n[required closure information processed]\n/ record completion; update relationship
IN_PROGRESS --> CANCELLED : Brand.cancelCollaboration\n[obligations resolved] / record reason
COMPLETED : submitReview [no review yet] / record Brand evaluation
COMPLETED : viewHistory / read retained outcomes and fees
CANCELLED --> [*]
note right of COMPLETED
Completion does not imply a review exists
or that historical evidence is validated.
Learning is requested separately after
Campaign completion and evidence validation.
Missing KPI or fee data remains unknown.
end note
note right of IN_PROGRESS
This is the basic recording workflow.
Content submission / approval states
are shown separately in appendix A1.
end note
''', 'Vòng đời hợp tác cơ bản; hoàn tất hợp tác, gửi review và xác thực dữ liệu học là các việc khác nhau.')

add('04-state-user-account', 'User Account Status', 'FE-11.1-3; Admin screens 6-7; Registration section 4.1',
    'SRS account status names | Suspension and restoration rules proposed', r'''
[*] --> ACTIVE : registrationOrProvisioningCompleted
ACTIVE --> LOCKED : Admin.lockAccount [authorized] / record reason
LOCKED -[norank]-> ACTIVE : Admin.unlockAccount [authorized]
ACTIVE --> SUSPENDED : Admin.suspendAccount [authorized] / record reason
LOCKED --> SUSPENDED : Admin.suspendAccount [authorized]
SUSPENDED -[norank]-> ACTIVE : Admin.restoreAccount [issue resolved]
ACTIVE : login [credentials and verification valid] / create session
ACTIVE : logout / end session
LOCKED : login / deny access
SUSPENDED : login / deny access
note bottom of ACTIVE
Account status is separate from role,
email verification and login session state.
Locking or suspension should invalidate sessions
and retain campaign and audit history.
end note
''', 'ACTIVE, LOCKED, SUSPENDED theo phần màn hình quản trị; không trộn trạng thái đăng nhập vào vòng đời tài khoản.')

add('05-state-creator-claim', 'Creator Ownership Claim', 'Registration section 4.3; CreatorClaimStatus; CreatorClaimService',
    'Registration requires claiming | Manual review states refined from current Java code', r'''
[*] --> PENDING : Creator.submitClaim\n[eligible account; evidence supplied]
PENDING --> APPROVED : Reviewer.approve\n[proof valid; eligible claimant; channel unowned]\n/ link channel to CreatorMaster
PENDING --> REJECTED : Reviewer.reject\n[proof insufficient] / record reason
PENDING --> REJECTED : competingClaimApproved\n/ preserve decision audit
APPROVED --> REVOKED : Reviewer.revoke\n[claim still owns channel]\n/ unlink ownership; retain public data
REJECTED --> [*]
REVOKED --> [*]
note right of APPROVED
The claim remains approved until revoked.
Ownership is not transferred by matching
an email, display name or social handle.
end note
note bottom of PENDING
This is a manual Claim request, not an OAuth session
or an account-registration invitation.
A rejected/revoked request remains historical;
a later claim is a new request.
end note
''', 'Claim thủ công theo yêu cầu xác minh Creator của bản đăng ký; trạng thái và điều kiện được làm rõ bằng code hiện có.')

add('06-state-subscription', 'Brand Subscription', 'UC47-49; Brand Plans / Upgrade; Registration section 4.1-2',
    'Document-led subscription lifecycle | Expiry and cancellation policy proposed', r'''
state planType <<choice>>
[*] --> planType : Brand.selectPlan [plan available]
planType --> PENDING : [paid plan] / create checkout
planType --> ACTIVE : [free plan and eligible]\n/ set validity period
PENDING --> ACTIVE : paymentConfirmed\n[verified success; no activation conflict]\n/ set validity period and entitlements
PENDING --> CANCELLED : paymentFailed / record failure
PENDING --> CANCELLED : Brand.cancelPending
ACTIVE : useFeature [within entitlement] / record usage
ACTIVE --> EXPIRED : validityPeriodEnded / stop paid access
ACTIVE --> CANCELLED : cancelApproved\n[authorized cancellation policy] / stop access
EXPIRED --> [*]
CANCELLED --> [*]
note right of ACTIVE
Quota exhaustion does not change status.
Quota and permissions are separate checks.
This design uses immediate cancellation;
the documents do not define the exact policy.
end note
note right of EXPIRED
Renewal or upgrade creates a new subscription
under the applicable billing policy.
The old record is retained for history.
end note
''', 'Gói trả phí chỉ kích hoạt khi xác nhận thanh toán thành công; hết quota không đồng nghĩa hết hạn gói.')

add('07-state-payment', 'Subscription Payment', 'UC47; Admin Transaction / Refund screens; Registration section 4.2',
    'SRS names: PENDING / SUCCESS / FAILED / REFUNDED | Refund rules proposed', r'''
[*] --> PENDING : Brand.checkout [paid plan selected]\n/ create transaction reference
PENDING --> SUCCESS : gatewayCallback\n[verified callback; success; activation allowed]\n/ record payment and activate subscription atomically
PENDING --> FAILED : gatewayCallback\n[verified callback; final failure]\n/ cancel pending subscription
PENDING : invalidCallback / reject; do not activate
PENDING : activationConflict / reject settlement; require resolution
SUCCESS : duplicateSuccessCallback / acknowledge without reactivation
FAILED : duplicateFailureCallback / acknowledge without repeat effects
SUCCESS --> REFUNDED : refundConfirmed\n[eligible request; authorized operator; gateway confirms]\n/ record refund and apply entitlement policy
FAILED --> [*]
REFUNDED --> [*]
note right of PENDING
A browser redirect alone is not payment proof.
Verify reference, amount and gateway authenticity.
Retry after FAILED creates a new Payment;
the previous transaction is not reset.
end note
note right of SUCCESS
This is payment for a subscription.
Creator fees are recorded separately;
the platform does not perform Creator payouts.
end note
''', 'Thanh toán gói theo tên trạng thái trong SRS; retry tạo giao dịch mới, callback lặp không kích hoạt gói lần nữa.')

add('08-state-collection-job', 'Creator Data Collection Job', 'FE-04.5, FE-04.12-13; UC71-73; Admin Collection Job screens',
    'SDD job-state proposal | Retry, rate limiting and cancellation derived from document functions', r'''
[*] --> PENDING : scheduledOrManualRefresh\n[authorized and within quota] / queue job
PENDING --> RUNNING : workerStarts [source available]
PENDING --> CANCELLED : Operator.cancelPending
RUNNING : do / retrieve and normalize public data
RUNNING --> SUCCEEDED : collectionCompleted\n[normalized data valid] / publish new M2 snapshot
RUNNING --> RATE_LIMITED : providerRateLimit / record retry time
RATE_LIMITED -[norank]-> PENDING : retryTimeReached [retry allowed]
RATE_LIMITED --> FAILED : retryBudgetExhausted / record error
RUNNING --> FAILED : collectionError / retain last valid snapshot
FAILED -[norank]-> PENDING : Operator.retry [temporary issue resolved]\n/ record new attempt
SUCCEEDED --> [*]
CANCELLED --> [*]
note right of FAILED
A failed refresh must not erase or overwrite
the latest successfully collected Creator data.
Attempt history and errors remain traceable.
end note
note right of SUCCEEDED
Job status differs from data freshness.
A successful snapshot can later become STALE;
the finished job remains SUCCEEDED.
end note
''', 'Job thu thập dữ liệu có retry/rate limit; lỗi refresh giữ nguyên snapshot hợp lệ trước đó.')

add('09-state-recommendation-run', 'Recommendation Run', 'FE-05.1-11; LI-12, LI-15; Recommendation Run Persistence',
    'Proposed execution lifecycle | No complete RecommendationRun implementation in this repository', r'''
[*] --> QUEUED : Brand.requestRecommendation\n[campaign context valid; access allowed]\n/ bind M3, M4 and configuration versions
QUEUED --> RUNNING : workerStarts
RUNNING : do / retrieve candidate pool and apply hard filters
RUNNING : do / evaluate available M1-M4 evidence
RUNNING : do / score, rank and calculate confidence
RUNNING : do / estimate fees when supported; explain results
RUNNING --> SUCCEEDED : evaluationCompleted\n[results and evidence references persisted]
RUNNING --> FAILED : processingError / retain diagnostics
SUCCEEDED --> [*]
FAILED --> [*]
note right of RUNNING
Missing M1 does not automatically reject a Creator.
Reflect missing evidence in confidence.
Never invent metrics, fees or outcomes.
No eligible candidate is a valid empty result,
not necessarily a technical failure.
end note
note right of SUCCEEDED
A retry or changed brief creates a new run.
Completed runs retain their original versions;
later Campaign changes do not rewrite them.
end note
''', 'Vòng đời một lần chạy recommendation; các bước lọc/chấm điểm là hoạt động bên trong RUNNING, không phải trạng thái Campaign.')

add('10-state-post-campaign-learning', 'Post-Campaign Learning Job', 'FE-09.6; FE-10.1-8; UC74-77; LI-20-21',
    'Proposed learning-job lifecycle | Evidence policy and retry rules require SDD agreement', r'''
[*] --> PENDING : campaignCompleted / queue learning job
PENDING --> VALIDATING : workerStarts
VALIDATING : do / inspect recorded outcome, fee and review evidence
VALIDATING --> WAITING_EVIDENCE : validationCompleted\n[no usable validated evidence] / record missing evidence
WAITING_EVIDENCE -[norank]-> VALIDATING : evidenceUpdated / recheck evidence
VALIDATING --> PROCESSING : validationCompleted\n[usable validated evidence exists]
PROCESSING : do / generate M1 from supported facts
PROCESSING : do / update learned M4 patterns when supported
PROCESSING : do / preserve M3 references and provenance
PROCESSING --> SUCCEEDED : learningCommitted\n[outputs consistent and traceable]
VALIDATING --> FAILED : technicalError / record diagnostics
PROCESSING --> FAILED : technicalError / preserve last valid memories
FAILED -[norank]-> PENDING : retryRequested [failure recoverable]\n/ resume safely without duplicate evidence
SUCCEEDED --> [*]
note right of WAITING_EVIDENCE
Missing evidence is not fabricated.
Do not require every optional KPI or review:
use validated facts that are actually available.
end note
note right of PROCESSING
Learned M4 must not overwrite explicit Brand Profile.
Current M3 takes precedence over learned preferences.
Shortlisting or contacting alone never triggers learning.
end note
''', 'Job học sau chiến dịch: thiếu bằng chứng thì chờ, chỉ sử dụng dữ liệu đã xác thực; không ghi đè Brand Profile bằng suy luận.')

add('A1-state-content-collaboration', 'Extended Collaboration: Content Approval',
    'Brand screens 22-23; broader than FE-08 core workflow',
    'OPTIONAL SRS SCREEN VARIANT | Not the implemented collaboration state machine', r'''
[*] --> ACCEPTED : collaborationInvitationAccepted
ACCEPTED --> CONTENT_IN_PROGRESS : Creator.startContent
CONTENT_IN_PROGRESS --> CONTENT_REVIEW : Creator.submitContent\n[deliverables supplied]
CONTENT_REVIEW -[norank]-> CONTENT_IN_PROGRESS : Brand.requestRevision / record comments
CONTENT_REVIEW --> APPROVED : Brand.approveContent\n[requirements satisfied]
APPROVED --> POSTED : publishedUrlConfirmed
POSTED --> COMPLETED : Brand.complete\n[required outcome information processed]
ACCEPTED --> CANCELLED : cancellationApproved [obligations resolved]
CONTENT_IN_PROGRESS --> CANCELLED : cancellationApproved [obligations resolved]
CONTENT_REVIEW --> CANCELLED : cancellationApproved [obligations resolved]
APPROVED --> CANCELLED : cancellationApproved [obligations resolved]
COMPLETED --> [*]
CANCELLED --> [*]
note right of CONTENT_REVIEW
Use this variant only if the team adopts
Creator-side submissions and content approval.
The core scope and exclusions do not consistently
include this workflow. It replaces diagram 03,
rather than adding states silently to its enum.
end note
''', 'Phụ lục: vòng đời duyệt nội dung từ phần mô tả màn hình; chỉ dùng nếu nhóm chọn phạm vi mở rộng.', extended=True)

add('A2-state-collaboration-invitation', 'Extended Collaboration Invitation',
    'Brand screen 21; conflicts with EX-02 marketplace exclusion',
    'OPTIONAL SRS SCREEN VARIANT | Distinct from Creator claim or registration invitations', r'''
[*] --> PENDING : Brand.sendInvitation\n[campaign active; offer and deadline valid]
PENDING --> ACCEPTED : Creator.accept\n[not expired; campaign permits collaboration]\n/ create accepted collaboration
PENDING --> DECLINED : Creator.decline / retain response
PENDING --> EXPIRED : invitationDeadlineReached
PENDING --> CANCELLED : Brand.withdraw [not yet answered]
ACCEPTED --> [*]
DECLINED --> [*]
EXPIRED --> [*]
CANCELLED --> [*]
note right of PENDING
This is an offer to join a Campaign.
It is not creator.model.CreatorInvitation
(channel ownership claim) or
auth.model.CreatorRegistrationInvitation.
The three invitation lifecycles are separate.
end note
note bottom of ACCEPTED
Do not use this variant in the basic external-contact MVP.
The documents must first agree on in-platform acceptance.
end note
''', 'Phụ lục: lời mời hợp tác theo màn hình; khác hoàn toàn lời mời claim kênh hay mời đăng ký Creator.', extended=True)

(OUT/'manifest.json').write_text(json.dumps(DIAGRAMS,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

options = '\n'.join(f'<option value="{x["id"]}">{escape(x["id"].split("-")[0]+" · "+x["title"])}</option>' for x in DIAGRAMS)
data = json.dumps(DIAGRAMS,ensure_ascii=False).replace('</','<\\/')
html = '''<!doctype html>
<html lang="vi"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>InfluencerMatch · State Diagrams</title>
<style>
*{box-sizing:border-box}body{margin:0;font:14px system-ui,sans-serif;color:#202428;background:#f5f5f3}header{padding:18px 24px;background:white;border-bottom:1px solid #ddd}h1{font-size:22px;margin:0 0 8px}p{margin:0;line-height:1.6;color:#5c6268}nav{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:12px 24px;background:white;border-bottom:1px solid #ddd}select,button{padding:8px 10px;font:inherit;background:white;border:1px solid #aaa;border-radius:4px}button{cursor:pointer}a{color:#225574;text-underline-offset:3px}#viewport{height:calc(100vh - 250px);min-height:420px;overflow:auto;padding:24px}#diagram{display:block;background:white;max-width:none}footer{padding:12px 24px;line-height:1.6;font-size:13px}#zoom{min-width:42px;text-align:center}button:focus-visible,a:focus-visible,select:focus-visible{outline:3px solid #447d9a;outline-offset:2px}@media print{nav{display:none}#viewport{height:auto;overflow:visible;padding:0}#diagram{max-width:100%;width:100%!important}footer{font-size:11px}}
</style>
<header><h1>InfluencerMatch · State Diagrams</h1><p>10 sơ đồ nghiệp vụ chính và 2 bản mở rộng từ tài liệu. Đây là thiết kế SRS/SDD; một số quy tắc chuyển trạng thái được đề xuất.</p><p><a href="README.md">Căn cứ, giả định và khác biệt với code</a> · <a href="../index.html">Mục lục sơ đồ</a></p></header>
<nav aria-label="Điều khiển sơ đồ"><select id="select" aria-label="Chọn sơ đồ">OPTIONS</select><button id="minus" aria-label="Thu nhỏ">−</button><output id="zoom">100%</output><button id="plus" aria-label="Phóng to">+</button><button id="fit">Vừa khung</button><button id="actual">100%</button><a id="svg" target="_blank" rel="noopener">SVG</a><a id="png" download>Tải PNG</a><a id="source" download>PlantUML</a></nav>
<main id="viewport"><img id="diagram" alt="State diagram"></main><footer><div id="summary"></div><div id="basis"></div></footer>
<script>
const items=DATA;const el=id=>document.getElementById(id);let ratio=1,natural=1;
function scale(v){ratio=Math.min(3,Math.max(.1,v));el('diagram').style.width=Math.round(natural*ratio)+'px';el('zoom').textContent=Math.round(100*ratio)+'%'}
function fit(){scale(Math.min(1,(el('viewport').clientWidth-48)/natural))}
function show(){const item=items.find(x=>x.id===el('select').value);el('diagram').onload=()=>{natural=el('diagram').naturalWidth;fit()};el('diagram').src='rendered/'+item.id+'.svg';el('diagram').alt=item.title;for(const format of ['svg','png'])el(format).href='rendered/'+item.id+'.'+format;el('source').href=item.id+'.puml';el('summary').textContent=item.summary;el('basis').textContent='Căn cứ: '+item.basis;el('viewport').scrollTo(0,0)}
el('select').onchange=show;el('minus').onclick=()=>scale(ratio/1.2);el('plus').onclick=()=>scale(ratio*1.2);el('fit').onclick=fit;el('actual').onclick=()=>scale(1);show();
</script></html>'''.replace('OPTIONS',options).replace('DATA',data)
(OUT/'index.html').write_text(html,encoding='utf-8')
print(f'Generated {len(DIAGRAMS)} state diagrams and offline viewer in {OUT}')
