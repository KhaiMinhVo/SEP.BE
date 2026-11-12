"""Generate curated UML views from the Java working tree (Python standard library only).

Run from any directory. Rendering is separate: see README.md.
Fields, selected method signatures, associations and injected dependencies come from code.
This is a documentation extractor for this repository, not a general Java parser.
"""
from pathlib import Path
import re
import json

OUT = Path(__file__).resolve().parent
ROOT = OUT.parents[1]
JAVA = ROOT / 'src/main/java/com/influencermatch/backend'
PREFIX = 'com.influencermatch.backend.'
SOURCES = {p.stem: p for p in JAVA.rglob('*.java')}
manifest = {}

def source(name):
    p = SOURCES[name]
    manifest[name] = p.relative_to(ROOT).as_posix()
    s = p.read_text(encoding='utf-8-sig')
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)

def short(s):
    return re.sub(r'(?:[a-z]\w*\.)+([A-Z]\w*)', r'\1', s).strip()

def fields(name):
    s = source(name)
    return [(short(m[0]), m[1]) for m in re.findall(
        r'\bprivate\s+(?!static\b)(?:final\s+)?([\w.]+(?:<[^;=\n]+>)?(?:\[\])?)\s+(\w+)\s*(?=[;=])', s)]

def methods(name, selected):
    s = source(name)
    result = []
    for method in selected:
        # Public class methods and implicitly-public interface methods.
        match = re.search(r'(?:public\s+)?(?:static\s+)?([\w.]+(?:<[^\n;{}]+>)?)\s+'
                          + re.escape(method) + r'\s*\(([^)]*)\)', s)
        if not match:
            raise ValueError(f'Method missing: {name}.{method}')
        result_type, params = match.groups()
        parts = re.split(r',\s*(?![^<]*>)', params.strip()) if params.strip() else []
        types = [short(re.sub(r'\s+\w+$', '', re.sub(r'\s+', ' ', p.strip()))) for p in parts]
        result.append(f'+ {method}({", ".join(types)}): {short(result_type)}')
    return result

STYLE = '''@startuml
skinparam backgroundColor white
skinparam shadowing false
skinparam roundcorner 0
skinparam defaultFontName Arial
skinparam defaultFontSize 13
skinparam classAttributeIconSize 0
skinparam packageStyle folder
skinparam classBackgroundColor white
skinparam classBorderColor #444444
skinparam classHeaderBackgroundColor #F2F2F2
skinparam packageBackgroundColor white
skinparam packageBorderColor #666666
skinparam ArrowColor #555555
skinparam nodesep 40
skinparam ranksep 65
hide circle
'''

def write(name, title, body, subtitle='Implemented Java classes | working tree: 2026-10-26'):
    # Keep explanations in README.md; exported report figures contain only UML.
    body = re.sub(r'^note\b.*?^end note\s*\n?', '', body, flags=re.M | re.S)
    body = re.sub(r'^legend\b.*?^endlegend\s*\n?', '', body, flags=re.M | re.S)
    text = STYLE + f'title {title}\n' + body + '\n@enduml\n'
    directory = OUT / ('package-diagram' if name == '01-package-diagram' else 'class-diagram')
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f'{name}.puml').write_text(text, encoding='utf-8')

def declaration(name, selected_fields=None, selected_methods=(), compact=False):
    s = source(name)
    if re.search(r'\benum\s+' + name + r'\b', s):
        body = s.split('{', 1)[1].split(';', 1)[0].split('}', 1)[0]
        values = [x.strip() for x in body.split(',') if x.strip()]
        return 'enum ' + name + ' {\n' + '\n'.join(values) + '\n}\n'
    kind = 'interface' if re.search(r'\binterface\s+' + name + r'\b', s) else 'class'
    if re.search(r'abstract\s+class\s+' + name, s):
        kind = 'abstract class'
    stereotype = ('entity' if re.search(r'@Entity\b', s) else 'mapped superclass' if '@MappedSuperclass' in s else 'service' if '@Service' in s else
                  'controller' if '@RestController' in s else 'repository' if 'repository' in SOURCES[name].parts else '')
    out = f'{kind} {name}' + (f' <<{stereotype}>>' if stereotype else '') + ' {\n'
    fs = fields(name)
    if selected_fields is not None:
        by_name = {n:t for t,n in fs}
        for n in selected_fields:
            if n == 'id' and n not in by_name and 'extends BaseEntity' in s:
                by_name[n] = 'UUID'
            if n not in by_name:
                raise ValueError(f'Field missing: {name}.{n}')
            out += f'- {n}: {by_name[n]}\n'
    elif not compact:
        for t,n in fs:
            if t not in SOURCES and not t.startswith('List<Creator>'):
                out += f'- {n}: {t}\n'
    if selected_methods:
        out += '--\n' + '\n'.join(methods(name, selected_methods)) + '\n'
    return out + '}\n'

def associations(names, omit=()):
    edges = []
    for name in names:
        s = source(name)
        # A scalar field starts after its annotation block and ends at the semicolon.
        for match in re.finditer(r'@(ManyToOne|OneToOne)\b([^;]*?)\bprivate\s+([\w.]+)\s+(\w+)\s*;', s, re.S):
            relation, annotation, target, member = match.groups()
            target = short(target)
            if target not in names or (name, member) in omit:
                continue
            required = bool(re.search(r'(optional|nullable)\s*=\s*false', annotation))
            target_mult = '1' if required else '0..1'
            source_mult = '0..*' if relation == 'ManyToOne' else '0..1'
            edges.append(f'{name} "{source_mult}" --> "{target_mult}" {target} : {member}')
    return edges

def diagram(filename, title, specs, enums=(), extra=(), omit=(), overview=False):
    names = list(specs) + list(enums)
    body = []
    for name, spec in specs.items():
        body.append(declaration(name, spec.get('fields'), spec.get('methods', ()), spec.get('compact', False)))
    body.extend(declaration(name) for name in enums)
    body.extend(associations(names, omit))
    for name in specs:
        s = source(name)
        for target in names:
            if name == target:
                continue
            if re.search(r'\bimplements\s+' + target + r'\b', s):
                body.append(f'{name} ..|> {target}')
            if re.search(r'\bextends\s+' + target + r'\b', s):
                body.append(f'{name} --|> {target}')
        for t, field in fields(name):
            if t in enums:
                body.append(f'{name} ..> {t} : {field}')
            elif t in specs and re.search(r'private\s+final\s+(?:[\w.]*\.)?' + t + r'\s+' + field + r'\b', s):
                body.append(f'{name} ..> {t}')
    body.extend(extra)
    body.append('legend bottom left\n'
                'Solid arrow: navigable entity association; end labels: multiplicity\n'
                'Dashed arrow: selected dependency; hollow triangle: inheritance / realization\n'
                'Selected members only; Lombok accessors and supporting DTOs omitted.\n'
                + ('Inherited id is shown inline; common timestamps / inheritance edges omitted.\n' if overview else
                   'BaseEntity inheritance omitted unless drawn; see source-index.json.\n')
                + 'endlegend')
    write(filename, title, '\n'.join(body))

# Actual feature packages. Every inner box is an existing package, not an invented layer.
package_rows = [['auth','user','security'], ['brand','campaign','discovery'],
                ['creator','relationship','collaboration'], ['billing','audit','taxonomy'],
                ['config','exception','common']]
packages = [name for row in package_rows for name in row]
body = ['package "com.influencermatch.backend" {']
for name in packages:
    subs = sorted({str(p.relative_to(JAVA / name).parent).replace('\\','.')
                   for p in (JAVA / name).rglob('*.java')} - {'.'})
    body.append(f'package "{name}" as p_{name} {{')
    if subs:
        contents = '**Subpackages**\\n' + '\\n'.join(subs)
        if name == 'common':
            contents += '\\n**Base classes**\\nBaseEntity, BaseRepository\\nBaseService, BaseController'
    else:
        contents = '**Classes**\\n' + '\\n'.join(p.stem for p in sorted((JAVA/name).glob('*.java')))
    body.append(f'rectangle "{contents}" as p_{name}_contents')
    body.append('}')
body.append('}')
for row in package_rows:
    for a,b in zip(row,row[1:]):
        body.append(f'p_{a}_contents -[hidden]right-> p_{b}_contents')
for row_a,row_b in zip(package_rows,package_rows[1:]):
    for a,b in zip(row_a,row_b):
        body.append(f'p_{a}_contents -[hidden]down-> p_{b}_contents')
# Selected cross-feature dependencies; validated by symbol use outside import statements.
package_edges = [('auth','user'),('auth','security'),('user','audit'),('brand','user'),('campaign','brand'),('campaign','creator'),('discovery','campaign'),('discovery','creator'),('relationship','campaign'),('relationship','creator'),('collaboration','relationship'),('collaboration','campaign'),('collaboration','creator'),('billing','brand'),('billing','audit'),('creator','relationship'),('security','user'),('config','security')]
for a,b in package_edges:
    target_symbols = [p.stem for p in (JAVA/b).rglob('*.java')]
    texts = '\n'.join(re.sub(r'^import .*?;', '', p.read_text(encoding='utf-8-sig'), flags=re.M) for p in (JAVA/a).rglob('*.java'))
    assert any(re.search(r'\b'+sym+r'\b', texts) for sym in target_symbols), (a,b)
    body.append(f'p_{a} .[norank].> p_{b}')
body += [
         'legend bottom left\nPackage contents reflect Java source directories. Dashed arrows show selected cross-feature dependencies.\nCommon / exception / security / audit dependencies are reduced for readability.\nFlyway scripts: src/main/resources/db/migration (resources, not a Java package).\nAI service implementation and frontend are outside this repository.\nendlegend']
write('01-package-diagram','InfluencerMatch Backend | Package Diagram', '\n'.join(body), 'Actual Java packages | selected dependencies | 2026-10-26')

def f(*names): return {'fields': names}
def m(*names): return {'compact': True, 'methods': names}

diagram('02-class-overview', 'InfluencerMatch Backend | Core Class Diagram', {
 'User': f('id','email','fullName','role','status'),
 'BrandProfile': f('id','businessName','industry'),
 'BrandContext': f('id','contextVersion','profileContext','learnedPatterns'),
 'Campaign': f('id','name','objective','status','budgetMin','budgetMax'),
 'CampaignContext': f('id','contextVersion','contextData','active'),
 'CreatorMaster': f('id','canonicalName','status'),
 'Creator': f('id','platform','externalId','displayName','sourceType'),
 'PublicCreatorMetric': f('id','followers','engagementRate','freshnessStatus','dataConfidence'),
 'Post': f('id','platformPostId','postUrl','views'),
 'ShortlistItem': f('id','priority','note'),
 'BrandCreatorRelationship': f('id','stage','quotedFee','agreedFee'),
 'Collaboration': f('id','agreedFee','status','paymentStatus'),
 'CampaignOutcome': f('id','kpiType','kpiTarget','kpiActual','actualFee'),
 'CreatorReview': f('id','overallRating','brandFitRating','wouldCollaborateAgain'),
 'EvidenceAsset': f('id','assetType','storageUrl'),
 'Plan': f('id','planName','price','campaignQuota'),
 'Subscription': f('id','status','expirationDate'),
 'Payment': f('id','amount','status','transactionCode'),
}, omit=[('PublicCreatorMetric','qualityReviewedBy'),('ShortlistItem','user'),('ShortlistItem','master'),
         ('BrandCreatorRelationship','user'),('BrandCreatorRelationship','master'),('Collaboration','master'),
         ('CreatorReview','reviewedBy'),('CreatorReview','collaboration'),('Payment','brandProfile')], overview=True,
 extra=['note top of CreatorMaster\nCreatorMaster = claimed identity linked to a User.\nCreator = one social account; master may be null.\nend note'])

diagram('03-class-campaign-discovery', 'Brand, Campaign and Discovery | Class Diagram', {
 'BaseEntity': {}, 'BrandProfile': f('businessName','industry','targetAudiences','preferredPlatforms'),
 'BrandContext': {'fields':['contextVersion','profileContext','learnedPatterns','evidenceRefs'],'methods':['updateProfileContext']},
 'Campaign': f('name','objective','platforms','niches','followerMin','followerMax','budgetMin','budgetMax','status'),
 'CampaignContext': f('contextVersion','contextData','active','archivedAt'),
 'BrandService': m('create','update'), 'CampaignService': m('create','changeStatus','discoverCreators'),
 'GeminiCampaignAutofillService': m('autofill'), 'CreatorDiscoveryService': m('discoverCreators'),
 'AiServiceClient': m('getEmbedding'), 'CampaignRepository': m(), 'PublicCreatorMetricRepository': m(),
 'Creator': f('id','platform','externalId','displayName'),
 'PublicCreatorMetric': f('id','followers','engagementRate','niche','dataConfidence'),
}, enums=['CampaignStatus','CampaignObjective'], extra=[
 'BrandService ..> BrandProfile : manages', 'BrandService ..> BrandContext : updates',
 'CampaignRepository ..> Campaign : persists', 'CampaignService ..> CampaignContext : versions',
 'PublicCreatorMetricRepository ..> PublicCreatorMetric : queries',
 'note right of CreatorDiscoveryService\nHybrid lexical + semantic discovery.\nNot the complete M1-M4 recommendation engine.\nend note',
 'note right of GeminiCampaignAutofillService\nCalls Gemini HTTP API for campaign autofill.\nIndependent from the Python embedding client.\nend note'])

diagram('04-class-shortlist-collaboration', 'Shortlist, CRM and Campaign Outcomes | Class Diagram', {
 'ShortlistService': m('addCreatorToShortlist','getShortlistByCampaign','removeCreatorFromShortlist'),
 'ShortlistServiceImpl': m(), 'RelationshipService': m('createRelationship','updateStage','updateDetails'),
 'RelationshipServiceImpl': m(), 'CampaignOwnershipService': m('requireOwned'),
 'CollaborationService': m('create','recordOutcome','review'),
 'Campaign': f('id','name','status'), 'Creator': f('id','platform','displayName'),
 'ShortlistItem': f('id','priority','note','addedAt'),
 'BrandCreatorRelationship': f('id','stage','contactMethod','quotedFee','agreedFee','nextAction'),
 'Collaboration': f('id','agreedFee','currency','status','paymentStatus','publishedContentUrl'),
 'CampaignOutcome': f('id','kpiType','kpiTarget','kpiActual','actualFee','revenue','roi','evidenceQuality'),
 'CreatorReview': f('id','overallRating','brandFitRating','wouldCollaborateAgain','note'),
 'EvidenceAsset': f('id','assetType','storageUrl','source'),
}, enums=['ShortlistPriority','RelationshipStage','CollaborationStatus','CollaborationPaymentStatus'], extra=[
 'ShortlistServiceImpl ..> ShortlistItem : manages via repository',
 'RelationshipServiceImpl ..> BrandCreatorRelationship : manages via repository',
 'CollaborationService ..> Collaboration : creates', 'CollaborationService ..> CampaignOutcome : records',
 'CollaborationService ..> CreatorReview : records',
 'note bottom of EvidenceAsset\nEntity/repository exist. Outcome request does not\ncurrently create EvidenceAsset records.\nend note'])

diagram('05-class-creator-identity', 'Creator Identity, Claim and Public Intelligence | Class Diagram', {
 'User': f('id','email','role','status'), 'CreatorMaster': f('id','canonicalName','status'),
 'Creator': {'fields':['id','platform','externalId','displayName','isStableId','sourceType'],'methods':['getOwner']},
 'PublicCreatorMetric': f('id','followers','engagementRate','niche','contentSummary','freshnessStatus','dataConfidence','collectedAt'),
 'Post': f('id','platformPostId','postUrl','views','likes'),
 'CreatorClaim': f('id','status','evidenceUrls','note','reason'),
 'CreatorInvitation': f('id','invitedEmail','status','expiresAt'),
 'CreatorProviderIdentity': f('id','provider','providerUserId','providerUsername'),
 'CreatorAccountVerification': f('id','verificationMethod','grantedScopes','status','verifiedAt'),
 'CreatorClaimService': m('claim','review','revoke'),
 'MetaOAuthService': m('callback','instagramCallback','confirm'),
 'MetaOwnershipService': m(), 'MetaSessionStore': m(),
 'MetaOAuthSession': f('id','platform','oauthFlow','status','expiresAt','confirmedAccountId'),
}, enums=['CreatorClaimStatus','CreatorSourceType','MetricDataStatus','OAuthFlow'],
 omit=[('PublicCreatorMetric','qualityReviewedBy'),('CreatorClaim','reviewer'),('CreatorClaim','revokedBy'),('CreatorInvitation','invitedBy'),('CreatorAccountVerification','verifiedByUser')], extra=[
 'CreatorClaimService ..> CreatorClaim : reviews via repository',
 'CreatorClaimService ..> CreatorMaster : resolves identity',
 'MetaSessionStore ..> MetaOAuthSession : persists via repository',
 'MetaOwnershipService ..> Creator : grants verified ownership',
 'note bottom of Creator\ngetOwner() derives User from master.\nThere is no persisted Creator.owner association.\nend note'])

diagram('06-class-auth-rbac', 'Authentication and Permission RBAC | Class Diagram', {
 'BaseEntity': {}, 'User': {'fields':['email','passwordHash','fullName','role','status','authVersion','emailVerified'],'methods':['getAuthorities','isEnabled']},
 'AuthService': m('register','login','refresh','logout','getProfile'), 'AuthServiceImpl': m(),
 'RefreshTokenService': m(), 'EmailVerificationService': m(),
 'RefreshToken': {'fields':['id','userId','tokenHash','expiresAt','revokedAt'],'methods':['active']},
 'EmailVerificationOtp': f('userId','codeHash','expiresAt','attempts'),
 'CreatorRegistrationInvitation': f('id','email','expiresAt','consumedAt'),
 'JwtTokenProvider': m(), 'JwtAuthenticationFilter': m(),
 'EffectivePermissionService': m('resolve','names'),
 'RolePermissionOverrideRepository': m(), 'RolePermissionOverride': f('id','role','permission','enabled'),
 'RolePermissionConfig': f('id','role','version'),
}, enums=['Role','UserStatus'], extra=[
 'RefreshToken ..> User : userId (UUID reference)',
 'EmailVerificationOtp ..> User : userId (UUID reference)',
 'RolePermissionOverrideRepository ..> RolePermissionOverride : persists',
 'note bottom of CreatorRegistrationInvitation\nAccount-registration invitation is separate from\ncreator.CreatorInvitation (channel-claim invitation).\nend note',
 'note bottom of RolePermissionConfig\nPermission is an enum in security.permission.\nIts large catalog is omitted in this view.\nend note'])

diagram('07-class-billing', 'Subscriptions and VNPay Payments | Class Diagram', {
 'BrandProfile': f('id','businessName'),
 'Plan': f('id','planName','price','currency','durationDays','campaignQuota','recommendationQuota','refreshQuota','status'),
 'Subscription': f('id','startDate','expirationDate','status','usedCampaignQuota','usedRecommendationQuota','usedRefreshQuota'),
 'Payment': f('id','amount','currency','paymentGateway','transactionCode','status','paidAt'),
 'PlanService': m('createPlan','updatePlan','deactivatePlan'),
 'SubscriptionService': m('subscribeBrandToPlan','cancelSubscription'),
 'PaymentService': m('createCheckout','processVnPayReturn'),
 'PaymentSettlementService': m('lock','apply'),
 'PlanRepository': m(), 'SubscriptionRepository': m(), 'PaymentRepository': m(), 'AuditService': m(),
}, enums=['PlanStatus','SubscriptionStatus','PaymentStatus'], extra=[
 'PlanRepository ..> Plan : persists', 'SubscriptionRepository ..> Subscription : persists',
 'PaymentRepository ..> Payment : persists',
 'note bottom of Payment\nPayment is subscription billing.\nCollaborationPaymentStatus only tracks campaign fee status.\nend note'])

(OUT/'class-diagram'/'source-index.json').write_text(json.dumps(dict(sorted(manifest.items())), indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
print(f'Generated 7 diagrams from {len(manifest)} Java source files in {OUT}')
