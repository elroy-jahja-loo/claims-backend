package local.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
public class Application {
  public static void main(String[] args) { SpringApplication.run(Application.class,args); }
  static final HttpSessionCsrfTokenRepository TOKENS = new HttpSessionCsrfTokenRepository();
  static final HttpSessionSecurityContextRepository CONTEXTS = new HttpSessionSecurityContextRepository();
  @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }

  @Bean SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
    http.csrf(csrf -> csrf.csrfTokenRepository(TOKENS).csrfTokenRequestHandler(new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler()))
      .authorizeHttpRequests(auth -> auth.requestMatchers("/", "/index.html", "/api/csrf", "/api/session").permitAll().anyRequest().authenticated())
      .securityContext(ctx -> ctx.securityContextRepository(CONTEXTS))
      .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> error(res,json,401,"AUTH_REQUIRED"))
        .accessDeniedHandler((req,res,ex) -> error(res,json,403, ex instanceof org.springframework.security.web.csrf.CsrfException ? "CSRF_INVALID" : "FORBIDDEN")))
      .logout(l -> l.disable()).formLogin(l -> l.disable()).httpBasic(l -> l.disable());
    return http.build();
  }
  static void error(HttpServletResponse res,ObjectMapper json,int status,String code) throws java.io.IOException {
    res.setStatus(status); res.setContentType("application/json");
    json.writeValue(res.getWriter(),Map.of("error",Map.of("code",code,"message",code.replace('_',' ').toLowerCase())));
  }

  @Bean CommandLineRunner seeds(JdbcTemplate db, PasswordEncoder encoder, ObjectMapper json,
      @Value("${spring.profiles.active:local}") String profile, @Value("${CLAIMS_TEST_PASSWORD:}") String testPassword) {
    return args -> {
      invariants(db);
      UUID primary = UUID.nameUUIDFromBytes("claims-primary-team".getBytes());
      db.update("insert into teams(id,name) values (?,?) on conflict do nothing",primary,"Singapore claims");
      db.update("insert into markets(code,currency,name) values ('SG','SGD','Singapore') on conflict do nothing");
      for(String type: new String[]{"MOTOR","PROPERTY"}) db.update("insert into routes(market,type,team_id) values ('SG',?,?) on conflict do nothing",type,primary);
      if (profile.equals("api-test-only")) {
        if (testPassword.isBlank()) throw new IllegalStateException("CLAIMS_TEST_PASSWORD is required for test profile");
        JsonNode fixtures=json.readTree(Application.class.getResourceAsStream("/fixtures.json"));
        UUID other=UUID.nameUUIDFromBytes("claims-other-team".getBytes());
        db.update("insert into teams(id,name) values (?,?) on conflict do nothing",other,"Fixture team");
        db.update("insert into markets(code,currency,name) values ('M2','USD','Fixture market') on conflict do nothing");
        for(String type: new String[]{"MOTOR","PROPERTY"}) db.update("insert into routes(market,type,team_id) values ('M2',?,?) on conflict do nothing",type,primary);
        var accounts=fixtures.path("accounts").fields();
        while(accounts.hasNext()) {
          var entry=accounts.next(); JsonNode a=entry.getValue();
          seed(db,encoder,a.path("email").asText(),a.path("role").asText(),a.path("market").isMissingNode()?null:a.path("market").asText(),
            a.path("team").isMissingNode()?null:a.path("team").asText().equals("other")?other:primary,testPassword);
        }
      } else {
        for (String[] a: new String[][]{{"a@local.test","CLAIMANT","claimant-a"},{"b@local.test","CLAIMANT","claimant-b"},{"o1@local.test","OFFICER","officer-o1"},{"o2@local.test","OFFICER","officer-o2"}})
          seed(db,encoder,a[0],a[1],a[1].equals("CLAIMANT")?"SG":null,a[1].equals("OFFICER")?primary:null,Files.readString(Path.of("/secrets",a[2]+".password")).trim());
      }
    };
  }
  static void seed(JdbcTemplate db,PasswordEncoder enc,String email,String role,String market,UUID team,String password) {
    if(password.isBlank()) throw new IllegalStateException("Seed credential must not be empty");
    var existing=db.queryForList("select password from accounts where email=?",email);
    if(!existing.isEmpty() && enc.matches(password,(String)existing.getFirst().get("password"))) return;
    // The persistent local credential is the source of truth; keep claim/account IDs unchanged.
    db.update("insert into accounts(id,email,password,role,market,team_id) values (?,?,?,?,?,?) on conflict(email) do update set password=excluded.password",
      UUID.nameUUIDFromBytes(email.getBytes()),email,enc.encode(password),role,market,team);
  }
  static void constraint(JdbcTemplate db,String table,String name,String definition) {
    if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from pg_constraint where conrelid=?::regclass and conname=?)",Boolean.class,table,name)))
      db.execute("alter table "+table+" add constraint "+name+" "+definition);
  }
  static void invariants(JdbcTemplate db) {
    constraint(db,"markets","markets_currency_pair","unique(code,currency)");
    constraint(db,"routes","routes_team_pair","unique(market,type,team_id)");
    constraint(db,"accounts","accounts_scope","check ((role='CLAIMANT' and market is not null and team_id is null) or (role='OFFICER' and team_id is not null and market is null))");
    constraint(db,"accounts","accounts_email_normalized","check (email=lower(trim(email)))");
    constraint(db,"accounts","accounts_market_pair","unique(id,market)");
    constraint(db,"accounts","accounts_team_pair","unique(id,team_id)");
    constraint(db,"claims","claim_owner_market","foreign key(claimant_id,market) references accounts(id,market)");
    constraint(db,"claims","claim_assignee_team","foreign key(assigned_officer_id,team_id) references accounts(id,team_id)");
    constraint(db,"claims","claim_market_currency","foreign key(market,currency) references markets(code,currency)");
    constraint(db,"claims","claim_team_route","foreign key(market,type,team_id) references routes(market,type,team_id)");
    constraint(db,"claims","claim_paid_owner","check (paid_at is null or paid_by=assigned_officer_id)");
    constraint(db,"claims","claim_decision_state","check ((status not in ('SETTLED','REJECTED') and decided_at is null and decision_reason is null and decision_amount is null and paid_at is null and paid_by is null) or (status='REJECTED' and decided_at is not null and nullif(trim(decision_reason),'') is not null and decision_amount is null and paid_at is null and paid_by is null) or (status='SETTLED' and decided_at is not null and nullif(trim(decision_reason),'') is not null and decision_amount is not null and decision_amount>0 and (paid_at is null)=(paid_by is null)))");
    constraint(db,"claims","claim_unpicked_report","check (status<>'REPORTED' or assigned_officer_id is null)");
    constraint(db,"claims","claim_coverage_reason","check ((coverage='UNKNOWN' and coverage_reason is null) or (coverage<>'UNKNOWN' and nullif(trim(coverage_reason),'') is not null))");
    constraint(db,"claims","claim_settlement_assessed","check (status<>'SETTLED' or (coverage='APPEARS_COVERED' and assessed_loss is not null))");
    constraint(db,"claims","claim_amounts_nonnegative","check (coalesce(requested_amount,0)>=0 and coalesce(assessed_loss,0)>=0 and coalesce(estimated_unpaid,0)>=0 and coalesce(decision_amount,0)>=0)");
    constraint(db,"claims","claims_type_check","check (type in ('MOTOR','PROPERTY'))");
    constraint(db,"claims","claims_version_check","check (version>0)");
    constraint(db,"claims","claims_status_check","check (status in ('REPORTED','UNDER_REVIEW','WAITING_FOR_CLAIMANT','SETTLED','REJECTED'))");
    constraint(db,"claims","claims_coverage_check","check (coverage in ('UNKNOWN','APPEARS_COVERED','APPEARS_NOT_COVERED'))");
    constraint(db,"routes","routes_type_check","check (type in ('MOTOR','PROPERTY'))");
    constraint(db,"information_requests","request_state","check ((status='OPEN' and answer is null and answered_at is null and withdrawal_reason is null) or (status='ANSWERED' and nullif(trim(answer),'') is not null and answered_at is not null and withdrawal_reason is null) or (status='WITHDRAWN' and answer is null and answered_at is null and nullif(trim(withdrawal_reason),'') is not null))");
    constraint(db,"information_requests","information_requests_status_check","check (status in ('OPEN','ANSWERED','WITHDRAWN'))");
    constraint(db,"claim_activity","claim_activity_actor_role_check","check (actor_role in ('CLAIMANT','OFFICER'))");
    constraint(db,"notification_outbox","outbox_claim_activity","foreign key(source_activity_id,claim_id) references claim_activity(id,claim_id)");
    constraint(db,"notification_outbox","notification_outbox_source_version_check","check (source_version>0)");
    constraint(db,"notification_outbox","notification_outbox_type_check","check (type in ('REQUESTED','ANSWERED','DECIDED'))");
    constraint(db,"notification_outbox","notification_outbox_destination_type_check","check (destination_type in ('ACCOUNT','TEAM'))");
    constraint(db,"dashboard_notifications","dashboard_notifications_type_check","check (type in ('REQUESTED','ANSWERED','DECIDED'))");
    constraint(db,"dashboard_notifications","dashboard_notifications_destination_type_check","check (destination_type in ('ACCOUNT','TEAM'))");
    db.execute("""
      create or replace function guard_claim_identity() returns trigger language plpgsql as $$
      begin
        if (old.id,old.reference,old.claimant_id,old.team_id,old.market,old.currency,old.type,old.incident_date,old.location,old.description,old.affected_asset,old.created_at)
          is distinct from (new.id,new.reference,new.claimant_id,new.team_id,new.market,new.currency,new.type,new.incident_date,new.location,new.description,new.affected_asset,new.created_at)
          or (old.assigned_officer_id is not null and new.assigned_officer_id is not null and old.assigned_officer_id<>new.assigned_officer_id)
          or ((old.status='REJECTED' or old.paid_at is not null) and old.assigned_officer_id is distinct from new.assigned_officer_id)
          or (old.decided_at is not null and (old.status,old.coverage,old.coverage_reason,old.assessed_loss,old.estimated_unpaid,old.decision_reason,old.decision_amount,old.decided_at)
            is distinct from (new.status,new.coverage,new.coverage_reason,new.assessed_loss,new.estimated_unpaid,new.decision_reason,new.decision_amount,new.decided_at))
          or (old.paid_at is not null and (old.paid_at,old.paid_by) is distinct from (new.paid_at,new.paid_by)) then
          raise exception 'Claim identity or decision is immutable' using errcode='23514';
        end if;
        return new;
      end $$
      """);
    db.execute("create or replace trigger claim_identity_guard before update on claims for each row execute function guard_claim_identity()");
    db.execute("""
      create or replace function deny_audit_changes() returns trigger language plpgsql as $$
      begin
        raise exception 'Audit entries are append-only' using errcode='23514';
      end $$
      """);
    db.execute("create or replace trigger history_append_only before update or delete on claim_activity for each row execute function deny_audit_changes()");
    db.execute("create or replace trigger idempotency_append_only before update or delete on idempotency_records for each row execute function deny_audit_changes()");
    db.execute("""
      create or replace function guard_request_history() returns trigger language plpgsql as $$
      begin
        if tg_op='DELETE' or old.status<>'OPEN' or (old.id,old.claim_id,old.officer_id,old.question,old.created_at)
           is distinct from (new.id,new.claim_id,new.officer_id,new.question,new.created_at) then
          raise exception 'Request or completed answer is immutable' using errcode='23514';
        end if;
        return new;
      end $$
      """);
    db.execute("create or replace trigger request_history_guard before update or delete on information_requests for each row execute function guard_request_history()");
  }

  @RestController
  static class Session {
    final JdbcTemplate db; final PasswordEncoder encoder;
    Session(JdbcTemplate db,PasswordEncoder encoder) { this.db=db; this.encoder=encoder; }
    @GetMapping("/api/csrf") Map<String,String> csrf(HttpServletRequest req) {
      req.getSession(); return Map.of("csrfToken",((CsrfToken)req.getAttribute(CsrfToken.class.getName())).getToken());
    }
    @PostMapping("/api/session") Map<String,Object> login(@RequestBody Map<String,String> input,HttpServletRequest req,HttpServletResponse res) {
      String email=input.getOrDefault("email","").trim().toLowerCase();
      var accounts=db.queryForList("select * from accounts where email=? and active=true",email);
      if(accounts.isEmpty() || !encoder.matches(input.getOrDefault("password",""),(String)accounts.getFirst().get("password")))
        throw new Fault(401,"BAD_CREDENTIALS");
      req.changeSessionId();
      SecurityContext ctx=SecurityContextHolder.createEmptyContext();
      ctx.setAuthentication(new UsernamePasswordAuthenticationToken(email,null,java.util.List.of()));
      SecurityContextHolder.setContext(ctx); CONTEXTS.saveContext(ctx,req,res);
      TOKENS.saveToken(null,req,res);
      var token=TOKENS.generateToken(req); TOKENS.saveToken(token,req,res);
      return Map.of("account",account(accounts.getFirst(),db),"csrfToken",token.getToken());
    }
    @GetMapping("/api/session") Map<String,Object> current(HttpServletRequest req) {
      return Map.of("account",account(actor(db),db),"csrfToken",((CsrfToken)req.getAttribute(CsrfToken.class.getName())).getToken());
    }
    @DeleteMapping("/api/session") ResponseEntity<Void> logout(HttpServletRequest req) {
      req.getSession().invalidate(); SecurityContextHolder.clearContext(); return ResponseEntity.noContent().build();
    }
  }
  static Map<String,Object> account(Map<String,Object> a,JdbcTemplate db) {
    if ("CLAIMANT".equals(a.get("role"))) {
      String market=(String)a.get("market");
      return Map.of("id",a.get("id"),"email",a.get("email"),"role",a.get("role"),"market",Map.of("code",market,"currency",db.queryForObject("select currency from markets where code=?",String.class,market)));
    }
    return Map.of("id",a.get("id"),"email",a.get("email"),"role",a.get("role"),"teamId",a.get("team_id"));
  }
  static Map<String,Object> actor(JdbcTemplate db) {
    var auth=SecurityContextHolder.getContext().getAuthentication();
    if(auth==null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) throw new Fault(401,"AUTH_REQUIRED");
    var rows=db.queryForList("select * from accounts where email=? and active=true",auth.getName());
    if(rows.isEmpty()) throw new Fault(401,"AUTH_REQUIRED"); return rows.getFirst();
  }
  static class Fault extends RuntimeException {
    final int status; final String code; final Integer version;
    Fault(int status,String code) { this(status,code,null); }
    Fault(int status,String code,Integer version) { super(code); this.status=status; this.code=code; this.version=version; }
  }
  @RestControllerAdvice static class Errors {
    @ExceptionHandler(Fault.class) ResponseEntity<?> fault(Fault f) {
      java.util.Map<String,Object> error=new java.util.LinkedHashMap<>();
      error.put("code",f.code); error.put("message",f.code.replace('_',' ').toLowerCase());
      if(f.version!=null) error.put("currentVersion",f.version);
      return ResponseEntity.status(f.status).body(Map.of("error",error));
    }
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<?> invalid(Exception ex) { return fault(new Fault(400,"INVALID_INPUT")); }
  }
}
