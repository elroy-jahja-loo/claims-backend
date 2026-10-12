package local.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import static local.claims.Application.*;

@RestController
@RequestMapping("/api")
public class Claims {
  final JdbcTemplate db; final ObjectMapper json; final TransactionTemplate tx;
  Claims(JdbcTemplate db,ObjectMapper json,org.springframework.transaction.PlatformTransactionManager manager) {
    this.db=db; this.json=json; this.tx=new TransactionTemplate(manager);
  }
  static Map<String,Object> obj(Object... pairs) {
    Map<String,Object> m=new LinkedHashMap<>();
    for(int i=0;i<pairs.length;i+=2) m.put((String)pairs[i],pairs[i+1]);
    return m;
  }
  String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
  Object decode(String value) { try { return json.readValue(value,Object.class); } catch(Exception e) { throw new IllegalStateException(e); } }
  static String text(JsonNode n,String name,boolean required) {
    JsonNode v=n.path(name);
    if(v.isMissingNode() || v.isNull()) { if(required) throw new Fault(400,"INVALID_INPUT"); return null; }
    if(!v.isTextual() || v.asText().length()>2000 || (required && v.asText().isBlank())) throw new Fault(400,"INVALID_INPUT");
    return v.asText().trim();
  }
  static String amount(JsonNode n,String name,String currency,boolean required,boolean positive) {
    String raw=text(n,name,required);
    if(raw==null) return null;
    try {
      if(!raw.matches("[0-9]+(\\.[0-9]+)?")) throw new NumberFormatException();
      BigDecimal value=new BigDecimal(raw);
      if(value.precision()>19 || value.compareTo(new BigDecimal("1000000000000000"))>=0 || (positive && value.signum()<=0)) throw new NumberFormatException();
      return value.setScale(Currency.getInstance(currency).getDefaultFractionDigits(),RoundingMode.UNNECESSARY).toPlainString();
    } catch(Exception e) { throw new Fault(400,"INVALID_INPUT"); }
  }
  static void only(JsonNode n,String... fields) {
    if(!n.isObject()) throw new Fault(400,"INVALID_INPUT");
    var names=java.util.Set.of(fields);
    n.fieldNames().forEachRemaining(f -> { if(!names.contains(f)) throw new Fault(400,"INVALID_INPUT"); });
  }
  static Instant instant(Object value) { return value instanceof OffsetDateTime o?o.toInstant():((java.sql.Timestamp)value).toInstant(); }
  static String iso(Object value) { return value==null?null:instant(value).toString(); }
  static String decimal(Object v,String currency) {
    return v==null?null:((BigDecimal)v).setScale(Currency.getInstance(currency).getDefaultFractionDigits(),RoundingMode.UNNECESSARY).toPlainString();
  }
  Map<String,Object> visible(UUID id,Map<String,Object> actor,boolean lock) {
    var found=db.queryForList("select * from claims where id=?"+(lock?" for update":""),id);
    if(found.isEmpty()) throw new Fault(404,"NOT_FOUND");
    Map<String,Object> c=found.getFirst();
    if("CLAIMANT".equals(actor.get("role")) ? !c.get("claimant_id").equals(actor.get("id")) : !c.get("team_id").equals(actor.get("team_id")))
      throw new Fault(404,"NOT_FOUND");
    return c;
  }
  void role(Map<String,Object> a,String expected) { if(!expected.equals(a.get("role"))) throw new Fault(403,"FORBIDDEN"); }
  void claimRole(UUID id,Map<String,Object> a,String expected) { visible(id,a,false); role(a,expected); }
  void owner(Map<String,Object> c,Map<String,Object> a) { if(!a.get("id").equals(c.get("assigned_officer_id"))) throw new Fault(403,"FORBIDDEN"); }
  void active(Map<String,Object> c) { if(List.of("SETTLED","REJECTED").contains(c.get("status"))) throw new Fault(409,"STATE_CONFLICT"); }
  String next(Map<String,Object> c) {
    return switch((String)c.get("status")) {
      case "REJECTED" -> "NONE";
      case "SETTLED" -> c.get("paid_at")==null?"OFFICER":"NONE";
      case "WAITING_FOR_CLAIMANT" -> "CLAIMANT";
      default -> "OFFICER";
    };
  }
  Map<String,Object> request(Map<String,Object> r) {
    return obj("id",r.get("id"),"claimId",r.get("claim_id"),"question",r.get("question"),"status",r.get("status"),
      "answer",r.get("answer"),"requestedAt",iso(r.get("created_at")),"answeredAt",iso(r.get("answered_at")));
  }
  List<Object> requests(UUID id) {
    return db.queryForList("select * from information_requests where claim_id=? order by created_at desc,id desc",id).stream().map(this::request).map(x -> (Object)x).toList();
  }
  Map<String,Object> view(Map<String,Object> c,Map<String,Object> a) {
    String currency=(String)c.get("currency"),status=(String)c.get("status");
    Map<String,Object> decision=c.get("decided_at")==null?null:obj("kind",status,"amount",decimal(c.get("decision_amount"),currency),"reason",c.get("decision_reason"),"decidedAt",iso(c.get("decided_at")));
    Map<String,Object> payment=!status.equals("SETTLED")?null:obj("status",c.get("paid_at")==null?"UNPAID":"PAID","confirmedAt",iso(c.get("paid_at")));
    Map<String,Object> out=obj("id",c.get("id"),"reference",c.get("reference"),"reportedAt",iso(c.get("created_at")),"market",c.get("market"),"currency",currency,"type",c.get("type"),
      "incident",obj("date",c.get("incident_date").toString(),"location",c.get("location"),"description",c.get("description"),"affectedAsset",c.get("affected_asset")),
      "policyReference",c.get("policy_reference"),"requestedAmount",decimal(c.get("requested_amount"),currency),"status",status,"nextActor",next(c),"decision",decision,
      "payment",payment,"informationRequests",requests((UUID)c.get("id")),"version",c.get("version"));
    if("OFFICER".equals(a.get("role"))) {
      out.put("teamId",c.get("team_id")); out.put("assignedOfficerId",c.get("assigned_officer_id"));
      out.put("assessment",obj("coverage",c.get("coverage"),"reason",c.get("coverage_reason"),"assessedLoss",decimal(c.get("assessed_loss"),currency),"estimatedUnpaidAmount",decimal(c.get("estimated_unpaid"),currency)));
      String exposure=status.equals("REJECTED") || c.get("paid_at")!=null ? decimal(BigDecimal.ZERO,currency)
        : status.equals("SETTLED") ? decimal(c.get("decision_amount"),currency) : decimal(c.get("estimated_unpaid"),currency);
      out.put("exposure",obj("known",exposure!=null,"amount",exposure));
      if(payment!=null) payment.put("confirmedByOfficerId",c.get("paid_by"));
    }
    return out;
  }
  Map<String,Object> updated(UUID id,Map<String,Object> a) { return view(visible(id,a,false),a); }
  UUID activity(UUID id,Map<String,Object> a,String type,Map<String,Object> details) {
    UUID activity=UUID.randomUUID();
    db.update("insert into claim_activity(id,claim_id,type,actor_id,actor_role,details) values (?,?,?,?,?,?)",activity,id,type,a.get("id"),a.get("role"),encode(details));
    return activity;
  }
  void notice(Map<String,Object> c,UUID source,String type,String dest,UUID destination) {
    db.update("insert into notification_outbox(id,claim_id,source_activity_id,source_version,type,destination_type,destination_id) values (?,?,?,?,?,?,?)",
      UUID.randomUUID(),c.get("id"),source,c.get("version"),type,dest,destination);
  }
  void bump(UUID id) { db.update("update claims set version=version+1,updated_at=now() where id=?",id); }
  Map<String,Object> req(UUID cid,UUID rid) {
    var rs=db.queryForList("select * from information_requests where id=? and claim_id=?",rid,cid);
    if(rs.isEmpty()) throw new Fault(404,"NOT_FOUND"); return rs.getFirst();
  }
  void open(Map<String,Object> r) { if(!"OPEN".equals(r.get("status"))) throw new Fault(409,"STATE_CONFLICT"); }
  boolean hasOpen(UUID id) { return !db.queryForList("select id from information_requests where claim_id=? and status='OPEN'",id).isEmpty(); }
  interface Work { ResponseEntity<Object> perform(); }
  ResponseEntity<Object> idempotent(Map<String,Object> a,String operation,String key,JsonNode body,Work work,UUID... resource) {
    if(key==null || key.isBlank()) throw new Fault(400,"IDEMPOTENCY_KEY_REQUIRED");
    if(key.length()>200) throw new Fault(400,"INVALID_INPUT");
    return tx.execute(t -> {
      db.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtext(?))) as locked",Integer.class,a.get("id")+":"+operation+":"+key);
      String fingerprint=encode(obj("body",body,"resource",resource));
      var existing=db.queryForList("select * from idempotency_records where actor_id=? and operation=? and client_key=?",a.get("id"),operation,key);
      if(!existing.isEmpty()) {
        var record=existing.getFirst();
        visible((UUID)record.get("claim_id"),a,false);
        try { if(!json.readTree((String)record.get("fingerprint")).equals(json.readTree(fingerprint))) throw new Fault(409,"IDEMPOTENCY_CONFLICT"); }
        catch(java.io.IOException e) { throw new IllegalStateException(e); }
        var result=ResponseEntity.status((Integer)record.get("status"));
        if(record.get("location")!=null) result.header("Location",(String)record.get("location"));
        return result.body(decode((String)record.get("body")));
      }
      ResponseEntity<Object> result=work.perform();
      @SuppressWarnings("unchecked") var payload=(Map<String,Object>)result.getBody();
      @SuppressWarnings("unchecked") var claim=(Map<String,Object>)payload.get("claim");
      db.update("insert into idempotency_records(actor_id,operation,client_key,fingerprint,claim_id,status,location,body) values (?,?,?,?,?,?,?,?)",
        a.get("id"),operation,key,fingerprint,claim.get("id"),result.getStatusCode().value(),result.getHeaders().getFirst("Location"),encode(payload));
      return result;
    });
  }
  @GetMapping("/claims/{id}") Map<String,Object> get(@PathVariable UUID id) { var a=actor(db); return obj("claim",view(visible(id,a,false),a)); }

  @PostMapping("/claims") ResponseEntity<Object> report(@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false) String key) {
    var a=actor(db); role(a,"CLAIMANT");
    return idempotent(a,"report",key,b,() -> {
      only(b,"type","incident","policyReference","requestedAmount","market","currency","teamId");
      String type=text(b,"type",true),market=(String)a.get("market");
      if(!List.of("MOTOR","PROPERTY").contains(type)) throw new Fault(400,"INVALID_INPUT");
      String currency=db.queryForObject("select currency from markets where code=?",String.class,market);
      var routes=db.queryForList("select team_id from routes where market=? and type=?",market,type);
      if(routes.isEmpty()) throw new Fault(400,"INVALID_INPUT"); UUID team=(UUID)routes.getFirst().get("team_id");
      if((b.has("market") && !market.equals(text(b,"market",true))) || (b.has("currency") && !currency.equals(text(b,"currency",true))) || (b.has("teamId") && !team.toString().equals(text(b,"teamId",true)))) throw new Fault(400,"INVALID_INPUT");
      JsonNode inc=b.path("incident"); only(inc,"date","location","description","affectedAsset");
      LocalDate date;
      try { date=LocalDate.parse(text(inc,"date",true)); } catch(Exception e) { throw new Fault(400,"INVALID_INPUT"); }
      String location=text(inc,"location",true),description=text(inc,"description",true),asset=text(inc,"affectedAsset",true);
      String policy=text(b,"policyReference",false),sum=amount(b,"requestedAmount",currency,false,false);
      if(policy!=null && policy.isBlank()) throw new Fault(400,"INVALID_INPUT");
      UUID id=UUID.randomUUID(); String reference="CLM-"+id.toString().toUpperCase();
      db.update("insert into claims(id,reference,claimant_id,team_id,market,currency,type,incident_date,location,description,affected_asset,policy_reference,requested_amount,status) values (?,?,?,?,?,?,?,?,?,?,?,?,?,'REPORTED')",
        id,reference,a.get("id"),team,market,currency,type,date,location,description,asset,policy,sum==null?null:new BigDecimal(sum));
      activity(id,a,"CLAIM_REPORTED",Map.of());
      return ResponseEntity.status(201).header("Location","/api/claims/"+id).body(obj("claim",updated(id,a)));
    });
  }
  @PostMapping("/claims/{id}/pickup") Map<String,Object> pickup(@PathVariable UUID id,@RequestBody JsonNode b) {
    var a=actor(db); claimRole(id,a,"OFFICER"); only(b);
    return tx.execute(t -> { var c=visible(id,a,true);
      if(c.get("assigned_officer_id")!=null) throw new Fault(409,"ALREADY_ASSIGNED");
      if("REJECTED".equals(c.get("status")) || c.get("paid_at")!=null) throw new Fault(409,"STATE_CONFLICT");
      db.update("update claims set assigned_officer_id=?,substantive=false,status=case when status='REPORTED' then 'UNDER_REVIEW' else status end where id=?",a.get("id"),id);
      bump(id); activity(id,a,"PICKUP",Map.of()); return obj("claim",updated(id,a)); });
  }
  @PostMapping("/claims/{id}/release") Map<String,Object> release(@PathVariable UUID id,@RequestBody JsonNode b) {
    var a=actor(db); claimRole(id,a,"OFFICER"); only(b,"reason");
    return tx.execute(t -> { var c=visible(id,a,true); owner(c,a);
      if("REJECTED".equals(c.get("status")) || c.get("paid_at")!=null) throw new Fault(409,"STATE_CONFLICT");
      String reason=text(b,"reason",false);
      if(Boolean.TRUE.equals(c.get("substantive")) && (reason==null || reason.isBlank())) throw new Fault(400,"REASON_REQUIRED");
      db.update("update claims set assigned_officer_id=null where id=?",id); bump(id);
      activity(id,a,"RELEASE",obj("reason",reason)); return obj("claim",updated(id,a)); });
  }

  @PostMapping("/claims/{id}/information-requests") ResponseEntity<Object> ask(@PathVariable UUID id,@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false) String key) {
    var a=actor(db); claimRole(id,a,"OFFICER");
    return idempotent(a,"request",key,b,() -> {
      var c=visible(id,a,true); owner(c,a); active(c); only(b,"question");
      String question=text(b,"question",true);
      if(hasOpen(id)) throw new Fault(409,"OPEN_REQUEST");
      UUID rid=UUID.randomUUID();
      db.update("insert into information_requests(id,claim_id,officer_id,question,status) values (?,?,?,?,'OPEN')",rid,id,a.get("id"),question);
      db.update("update claims set status='WAITING_FOR_CLAIMANT',substantive=true where id=?",id); bump(id);
      UUID source=activity(id,a,"INFORMATION_REQUESTED",Map.of("requestId",rid.toString()));
      notice(visible(id,a,false),source,"REQUESTED","ACCOUNT",(UUID)c.get("claimant_id"));
      return ResponseEntity.status(201).header("Location","/api/claims/"+id+"/information-requests/"+rid)
        .body(obj("request",request(req(id,rid)),"claim",updated(id,a)));
    },id);
  }
  @PostMapping("/claims/{id}/information-requests/{rid}/answer") ResponseEntity<Object> answer(@PathVariable UUID id,@PathVariable UUID rid,@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false) String key) {
    var a=actor(db); claimRole(id,a,"CLAIMANT");
    return idempotent(a,"answer",key,b,() -> {
      var c=visible(id,a,true); var r=req(id,rid); open(r);
      only(b,"answer","policyReference","requestedAmount"); String answer=text(b,"answer",true);
      String policy=b.has("policyReference")?text(b,"policyReference",true):null;
      String sum=b.has("requestedAmount")?amount(b,"requestedAmount",(String)c.get("currency"),true,false):null;
      db.update("update information_requests set status='ANSWERED',answer=?,answered_at=now() where id=?",answer,rid);
      db.update("update claims set status='UNDER_REVIEW',policy_reference=coalesce(?,policy_reference),requested_amount=coalesce(?,requested_amount) where id=?",
        policy,sum==null?null:new BigDecimal(sum),id); bump(id);
      UUID source=activity(id,a,"INFORMATION_RECEIVED",obj("requestId",rid,"policyReferenceBefore",c.get("policy_reference"),"policyReferenceAfter",policy==null?c.get("policy_reference"):policy,
        "requestedAmountBefore",decimal(c.get("requested_amount"),(String)c.get("currency")),"requestedAmountAfter",sum==null?decimal(c.get("requested_amount"),(String)c.get("currency")):sum));
      notice(visible(id,a,false),source,"ANSWERED","TEAM",(UUID)c.get("team_id"));
      return ResponseEntity.ok(obj("request",request(req(id,rid)),"claim",updated(id,a)));
    },id,rid);
  }
  @PostMapping("/claims/{id}/information-requests/{rid}/withdraw") Map<String,Object> withdraw(@PathVariable UUID id,@PathVariable UUID rid,@RequestBody JsonNode b) {
    var a=actor(db); claimRole(id,a,"OFFICER");
    return tx.execute(t -> { var c=visible(id,a,true); owner(c,a); var r=req(id,rid); open(r);
      only(b,"reason"); String reason=text(b,"reason",true);
      db.update("update information_requests set status='WITHDRAWN',withdrawal_reason=? where id=?",reason,rid);
      db.update("update claims set status='UNDER_REVIEW' where id=?",id); bump(id);
      activity(id,a,"REQUEST_WITHDRAWN",obj("reason",reason,"requestId",rid));
      return obj("request",request(req(id,rid)),"claim",updated(id,a)); });
  }
  @PutMapping("/claims/{id}/assessment") Map<String,Object> assess(@PathVariable UUID id,@RequestBody JsonNode b) {
    var a=actor(db); claimRole(id,a,"OFFICER");
    return tx.execute(t -> { var c=visible(id,a,true); owner(c,a); active(c);
      only(b,"coverage","reason","assessedLoss","estimatedUnpaidAmount");
      for(String field:new String[]{"coverage","reason","assessedLoss","estimatedUnpaidAmount"}) if(!b.has(field)) throw new Fault(400,"INVALID_INPUT");
      String coverage=text(b,"coverage",true),reason=text(b,"reason",false);
      if(!List.of("UNKNOWN","APPEARS_COVERED","APPEARS_NOT_COVERED").contains(coverage) || (coverage.equals("UNKNOWN") ? reason!=null : reason==null || reason.isBlank())) throw new Fault(400,"INVALID_INPUT");
      String loss=amount(b,"assessedLoss",(String)c.get("currency"),false,false),est=amount(b,"estimatedUnpaidAmount",(String)c.get("currency"),false,false);
      db.update("update claims set coverage=?,coverage_reason=?,assessed_loss=?,estimated_unpaid=?,substantive=true where id=?",coverage,reason,loss==null?null:new BigDecimal(loss),est==null?null:new BigDecimal(est),id);
      bump(id); activity(id,a,"ASSESSMENT",obj("coverage",coverage,"reason",reason,"assessedLoss",loss,"estimatedUnpaidAmount",est));
      return obj("claim",updated(id,a)); });
  }
  @PostMapping("/claims/{id}/decision") ResponseEntity<Object> decision(@PathVariable UUID id,@RequestBody JsonNode b,@RequestHeader(value="Idempotency-Key",required=false) String key) {
    var a=actor(db); claimRole(id,a,"OFFICER");
    return idempotent(a,"decision",key,b,() -> { var c=visible(id,a,true); owner(c,a); active(c);
      only(b,"kind","amount","reason","reviewedVersion");
      String kind=text(b,"kind",true),reason=text(b,"reason",true);
      if(!List.of("SETTLED","REJECTED").contains(kind) || !b.has("reviewedVersion") || !b.path("reviewedVersion").isIntegralNumber() || !b.has("amount")) throw new Fault(400,"INVALID_INPUT");
      if(b.path("reviewedVersion").asInt()!=((Number)c.get("version")).intValue()) throw new Fault(409,"STALE_VERSION",(Integer)c.get("version"));
      if(hasOpen(id)) throw new Fault(409,"OPEN_REQUEST");
      String sum=amount(b,"amount",(String)c.get("currency"),kind.equals("SETTLED"),kind.equals("SETTLED"));
      if(kind.equals("REJECTED") && sum!=null) throw new Fault(400,"INVALID_INPUT");
      if(kind.equals("SETTLED") && (!"APPEARS_COVERED".equals(c.get("coverage")) || c.get("coverage_reason")==null || c.get("assessed_loss")==null)) throw new Fault(409,"MISSING_ASSESSMENT");
      db.update("update claims set status=?,decision_reason=?,decision_amount=?,decided_at=now(),substantive=true where id=?",kind,reason,sum==null?null:new BigDecimal(sum),id);
      bump(id); UUID source=activity(id,a,"DECISION",obj("kind",kind,"reason",reason,"amount",sum));
      notice(visible(id,a,false),source,"DECIDED","ACCOUNT",(UUID)c.get("claimant_id"));
      return ResponseEntity.ok(obj("claim",updated(id,a))); },id);
  }
  @PostMapping("/claims/{id}/mark-paid") Map<String,Object> paid(@PathVariable UUID id,@RequestBody JsonNode b) {
    var a=actor(db); claimRole(id,a,"OFFICER"); only(b);
    return tx.execute(t -> { var c=visible(id,a,true); owner(c,a);
      if(!"SETTLED".equals(c.get("status"))) throw new Fault(409,"STATE_CONFLICT");
      if(c.get("paid_at")!=null) return (Map<String,Object>)decode((String)c.get("paid_response"));
      db.update("update claims set paid_at=now(),paid_by=? where id=?",a.get("id"),id); bump(id); activity(id,a,"MARK_PAID",Map.of());
      Map<String,Object> result=obj("claim",updated(id,a));
      db.update("update claims set paid_response=? where id=?",encode(result),id);
      return result; });
  }

  record Page(int limit,int offset) {}
  Page page(Integer limit,String cursor) {
    if(limit!=null && (limit<1 || limit>100)) throw new Fault(400,"INVALID_INPUT");
    try { int offset=cursor==null?0:Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));
      if(offset<0) throw new Exception(); return new Page(limit==null?20:limit,offset);
    } catch(Exception e) { throw new Fault(400,"INVALID_INPUT"); }
  }
  Map<String,Object> page(List<?> rows,Page p) {
    return obj("items",rows.size()>p.limit()?rows.subList(0,p.limit()):rows,
      "nextCursor",rows.size()>p.limit()?Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(p.offset()+p.limit()).getBytes(StandardCharsets.UTF_8)):null);
  }
  Map<String,Object> row(Map<String,Object> c,Map<String,Object> a) {
    Map<String,Object> r=obj("id",c.get("id"),"reference",c.get("reference"),"market",c.get("market"),"currency",c.get("currency"),"type",c.get("type"),"status",c.get("status"),"nextActor",next(c),"version",c.get("version"));
    if(a.get("role").equals("OFFICER")) {
      r.put("assignedOfficerId",c.get("assigned_officer_id")); r.put("teamId",c.get("team_id"));
      r.put("ageSeconds",Duration.between(instant(c.get("created_at")),Instant.now()).toSeconds());
      r.put("requestedAmount",decimal(c.get("requested_amount"),(String)c.get("currency")));
    }
    return r;
  }
  @GetMapping("/claims") Map<String,Object> claimantList(@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
    var a=actor(db); Page p=page(limit,cursor);
    String filter=a.get("role").equals("CLAIMANT")?"claimant_id":"team_id";
    Object owner=a.get("role").equals("CLAIMANT")?a.get("id"):a.get("team_id");
    var rows=db.queryForList("select * from claims where "+filter+"=? order by created_at desc,id desc limit ? offset ?",owner,p.limit()+1,p.offset());
    return page(rows.stream().map(c -> row(c,a)).toList(),p);
  }
  @GetMapping("/officer/claims") Map<String,Object> officerList(@RequestParam(defaultValue="team") String view,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
    var a=actor(db); role(a,"OFFICER"); Page p=page(limit,cursor);
    String where=switch(view) {
      case "unassigned" -> " and assigned_officer_id is null and (status not in ('REJECTED','SETTLED') or (status='SETTLED' and paid_at is null))";
      case "mine" -> " and assigned_officer_id='"+a.get("id")+"'";
      case "waiting" -> " and exists(select 1 from information_requests r where r.claim_id=claims.id and r.status='OPEN')";
      case "team" -> "";
      default -> throw new Fault(400,"INVALID_INPUT");
    };
    var rows=db.queryForList("select * from claims where team_id=?"+where+" order by created_at desc,id desc limit ? offset ?",a.get("team_id"),p.limit()+1,p.offset());
    return page(rows.stream().map(c -> row(c,a)).toList(),p);
  }
  @GetMapping("/claims/{id}/activity") Map<String,Object> history(@PathVariable UUID id,@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
    var a=actor(db); visible(id,a,false); Page p=page(limit,cursor);
    String safe="('CLAIM_REPORTED','INFORMATION_REQUESTED','INFORMATION_RECEIVED','REQUEST_WITHDRAWN','DECISION','DECISION_AVAILABLE','MARK_PAID')";
    var rows=db.queryForList("select * from claim_activity where claim_id=?"+(a.get("role").equals("CLAIMANT")?" and type in "+safe:"")+" order by occurred_at desc,id desc limit ? offset ?",id,p.limit()+1,p.offset());
    List<Object> result=new ArrayList<>();
    for(var r:rows) {
      if(a.get("role").equals("OFFICER")) result.add(obj("type",r.get("type").equals("DECISION_AVAILABLE")?"DECISION":r.get("type"),"actorId",r.get("actor_id"),"occurredAt",iso(r.get("occurred_at")),"details",decode((String)r.get("details"))));
      else {
        String type=(String)r.get("type"); if(type.equals("MARK_PAID")) type="PAYMENT_CONFIRMED"; if(type.equals("DECISION")) type="DECISION_AVAILABLE";
        result.add(obj("type",type,"occurredAt",iso(r.get("occurred_at")),"message",switch(type) {
          case "CLAIM_REPORTED" -> "Claim reported."; case "INFORMATION_REQUESTED" -> "Information requested.";
          case "INFORMATION_RECEIVED" -> "Information received."; case "REQUEST_WITHDRAWN" -> "Request closed.";
          case "DECISION_AVAILABLE" -> "Decision available."; default -> "Payment confirmed.";
        }));
      }
    }
    return page(result,p);
  }
  @GetMapping("/notifications") Map<String,Object> notifications(@RequestParam(required=false) Integer limit,@RequestParam(required=false) String cursor) {
    var a=actor(db); Page p=page(limit,cursor);
    String dest=a.get("role").equals("CLAIMANT")?"ACCOUNT":"TEAM";
    var rows=db.queryForList("select n.*,c.reference from dashboard_notifications n join claims c on c.id=n.claim_id where n.destination_type=? and n.destination_id=? and "+
      (dest.equals("ACCOUNT")?"c.claimant_id=?":"c.team_id=?")+" order by n.occurred_at desc,n.id desc limit ? offset ?",
      dest,dest.equals("ACCOUNT")?a.get("id"):a.get("team_id"),dest.equals("ACCOUNT")?a.get("id"):a.get("team_id"),p.limit()+1,p.offset());
    return page(rows.stream().map(r -> obj("id",r.get("id"),"type",r.get("type"),"claimId",r.get("claim_id"),"claimReference",r.get("reference"),"occurredAt",iso(r.get("occurred_at")))).toList(),p);
  }
  @GetMapping("/officer/team/summary") Map<String,Object> summary(@RequestParam("from") String from,@RequestParam("to") String to) {
    var a=actor(db); role(a,"OFFICER"); Instant start,end;
    try { start=Instant.parse(from); end=Instant.parse(to); if(!start.isBefore(end)) throw new Exception(); }
    catch(Exception e) { throw new Fault(400,"INVALID_INPUT"); }
    var totals=db.queryForMap("""
      select count(*) filter (where status not in ('REJECTED','SETTLED') or (status='SETTLED' and paid_at is null)) as open_count,
        count(*) filter (where status in ('REJECTED','SETTLED')) as decided_count,
        count(*) filter (where status='REJECTED' or (status='SETTLED' and paid_at is not null)) as resolved_count,
        count(*) filter (where decided_at >= ?::timestamptz and decided_at < ?::timestamptz) as decision_count,
        avg(extract(epoch from decided_at-created_at)) filter (where decided_at >= ?::timestamptz and decided_at < ?::timestamptz) as mean_seconds
      from claims where team_id=?
      """,start.toString(),end.toString(),start.toString(),end.toString(),a.get("team_id"));
    var currencies=db.queryForList("""
      with scoped as (select currency,case when status='REJECTED' or paid_at is not null then 0::numeric
        when status='SETTLED' then decision_amount else estimated_unpaid end as exposure from claims where team_id=?)
      select currency,coalesce(sum(exposure),0) as known, count(*) filter (where exposure is null) as unknown, count(*) as claims
      from scoped group by currency order by currency
      """,a.get("team_id"));
    return obj("teamId",a.get("team_id"),"openCount",totals.get("open_count"),"decidedCount",totals.get("decided_count"),
      "resolvedCount",totals.get("resolved_count"),"decisionCount",totals.get("decision_count"),
      "meanTimeToDecisionSeconds",totals.get("mean_seconds")==null?null:((BigDecimal)totals.get("mean_seconds")).doubleValue(),
      "exposureByCurrency",currencies.stream().map(r -> obj("currency",r.get("currency"),"knownOutstandingTotal",decimal(r.get("known"),(String)r.get("currency")),
        "unknownClaimCount",r.get("unknown"),"claimCount",r.get("claims"),"incomplete",((Number)r.get("unknown")).longValue()>0)).toList());
  }
}
