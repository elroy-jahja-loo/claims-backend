create table if not exists teams(id uuid primary key, name text not null);
create table if not exists markets(code text primary key, currency varchar(3) not null, name text not null, constraint markets_currency_pair unique(code,currency));
create table if not exists routes(market text references markets(code), type text not null check(type in ('MOTOR','PROPERTY')), team_id uuid references teams(id), primary key(market,type), constraint routes_team_pair unique(market,type,team_id));
create table if not exists accounts(id uuid primary key, email text not null unique, password text not null, role text not null check(role in ('CLAIMANT','OFFICER')), market text references markets(code), team_id uuid references teams(id), active boolean not null default true,
 constraint accounts_scope check ((role='CLAIMANT' and market is not null and team_id is null) or (role='OFFICER' and team_id is not null and market is null)),
 constraint accounts_email_normalized check (email=lower(trim(email))),
 constraint accounts_market_pair unique(id,market), constraint accounts_team_pair unique(id,team_id));
create table if not exists claims(
 id uuid primary key, reference text not null unique, claimant_id uuid not null references accounts(id), team_id uuid not null references teams(id), assigned_officer_id uuid references accounts(id),
 market text not null references markets(code), currency varchar(3) not null, type text not null check(type in ('MOTOR','PROPERTY')), incident_date date not null, location text not null, description text not null, affected_asset text not null,
 policy_reference text, requested_amount numeric(19,4) check(requested_amount>=0), status text not null check(status in ('REPORTED','UNDER_REVIEW','WAITING_FOR_CLAIMANT','SETTLED','REJECTED')), coverage text not null default 'UNKNOWN' check(coverage in ('UNKNOWN','APPEARS_COVERED','APPEARS_NOT_COVERED')), coverage_reason text, assessed_loss numeric(19,4) check(assessed_loss>=0), estimated_unpaid numeric(19,4) check(estimated_unpaid>=0),
 decision_reason text, decision_amount numeric(19,4) check(decision_amount>=0), decided_at timestamptz, paid_at timestamptz, paid_by uuid references accounts(id), paid_response text,
 substantive boolean not null default false, version integer not null default 1 check(version>0), created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
 constraint claim_owner_market foreign key(claimant_id,market) references accounts(id,market),
 constraint claim_assignee_team foreign key(assigned_officer_id,team_id) references accounts(id,team_id),
 constraint claim_market_currency foreign key(market,currency) references markets(code,currency),
 constraint claim_team_route foreign key(market,type,team_id) references routes(market,type,team_id),
 constraint claim_decision_state check ((status not in ('SETTLED','REJECTED') and decided_at is null and decision_reason is null and decision_amount is null and paid_at is null and paid_by is null)
    or (status='REJECTED' and decided_at is not null and nullif(trim(decision_reason),'') is not null and decision_amount is null and paid_at is null and paid_by is null)
    or (status='SETTLED' and decided_at is not null and nullif(trim(decision_reason),'') is not null and decision_amount is not null and decision_amount>0 and (paid_at is null)=(paid_by is null))),
 constraint claim_paid_owner check (paid_at is null or paid_by=assigned_officer_id),
 constraint claim_unpicked_report check (status<>'REPORTED' or assigned_officer_id is null),
 constraint claim_coverage_reason check ((coverage='UNKNOWN' and coverage_reason is null) or (coverage<>'UNKNOWN' and nullif(trim(coverage_reason),'') is not null)),
 constraint claim_settlement_assessed check (status<>'SETTLED' or (coverage='APPEARS_COVERED' and assessed_loss is not null)));
create index if not exists claims_team_queue on claims(team_id,status,assigned_officer_id,created_at desc,id desc);
create index if not exists claims_claimant_list on claims(claimant_id,created_at desc,id desc);
create table if not exists information_requests(id uuid primary key, claim_id uuid not null references claims(id), officer_id uuid not null references accounts(id), question text not null, status text not null check(status in ('OPEN','ANSWERED','WITHDRAWN')), answer text, created_at timestamptz not null default now(), answered_at timestamptz, withdrawal_reason text,
 constraint request_state check ((status='OPEN' and answer is null and answered_at is null and withdrawal_reason is null) or
        (status='ANSWERED' and nullif(trim(answer),'') is not null and answered_at is not null and withdrawal_reason is null) or
        (status='WITHDRAWN' and answer is null and answered_at is null and nullif(trim(withdrawal_reason),'') is not null)));
create unique index if not exists only_open_request on information_requests(claim_id) where status='OPEN';
create table if not exists claim_activity(id uuid primary key, claim_id uuid not null references claims(id), type text not null, actor_id uuid not null references accounts(id), actor_role text not null check(actor_role in ('CLAIMANT','OFFICER')), occurred_at timestamptz not null default now(), details text not null default '{}');
alter table claim_activity add column if not exists actor_role text;
update claim_activity a set actor_role=(select role from accounts where id=a.actor_id) where actor_role is null;
alter table claim_activity alter column actor_role set not null;
create index if not exists activity_list on claim_activity(claim_id,occurred_at desc,id desc);
create unique index if not exists activity_claim_pair on claim_activity(id,claim_id);
create table if not exists idempotency_records(actor_id uuid not null references accounts(id), operation text not null, client_key text not null, fingerprint text not null, claim_id uuid not null references claims(id), status integer not null, location text, body text not null, primary key(actor_id,operation,client_key));
create table if not exists notification_outbox(id uuid primary key, claim_id uuid not null references claims(id), source_activity_id uuid not null references claim_activity(id), source_version integer not null check(source_version>0), type text not null check(type in ('REQUESTED','ANSWERED','DECIDED')), destination_type text not null check(destination_type in ('ACCOUNT','TEAM')), destination_id uuid not null, occurred_at timestamptz not null default now(), delivered_at timestamptz, attempts integer not null default 0, next_attempt timestamptz not null default now(), last_error text);
alter table notification_outbox add column if not exists source_activity_id uuid references claim_activity(id);
update notification_outbox o set source_activity_id=a.id from claim_activity a where o.source_activity_id is null and o.claim_id=a.claim_id and o.occurred_at=a.occurred_at and a.type=case o.type when 'REQUESTED' then 'INFORMATION_REQUESTED' when 'ANSWERED' then 'INFORMATION_RECEIVED' else 'DECISION_AVAILABLE' end;
alter table notification_outbox alter column source_activity_id set not null;
create index if not exists pending_notices on notification_outbox(next_attempt) where delivered_at is null;
create table if not exists dashboard_notifications(id uuid primary key, claim_id uuid not null references claims(id), type text not null check(type in ('REQUESTED','ANSWERED','DECIDED')), destination_type text not null check(destination_type in ('ACCOUNT','TEAM')), destination_id uuid not null, occurred_at timestamptz not null, delivered_at timestamptz not null default now());
create index if not exists notice_list on dashboard_notifications(destination_type,destination_id,occurred_at desc,id desc);
