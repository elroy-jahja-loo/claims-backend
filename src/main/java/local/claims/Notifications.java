package local.claims;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableScheduling
class Notifications {
  final JdbcTemplate db; final ObjectMapper json; final KafkaTemplate<String,String> kafka; final TransactionTemplate tx;
  Notifications(JdbcTemplate db,ObjectMapper json,KafkaTemplate<String,String> kafka,org.springframework.transaction.PlatformTransactionManager manager) {
    this.db=db; this.json=json; this.kafka=kafka; this.tx=new TransactionTemplate(manager);
  }
  @Scheduled(fixedDelay=1500)
  void relay() {
    for(var event:db.queryForList("select * from notification_outbox where delivered_at is null and next_attempt<=now() order by occurred_at,id limit 50")) {
      UUID id=(UUID)event.get("id");
      try {
        String message=json.writeValueAsString(Map.of("id",id,"claimId",event.get("claim_id"),"sourceVersion",event.get("source_version"),
          "type",event.get("type"),"destinationType",event.get("destination_type"),"destinationId",event.get("destination_id"),
          "occurredAt",Claims.iso(event.get("occurred_at"))));
        kafka.send("claims.notifications.v1",id.toString(),message).get(3,TimeUnit.SECONDS);
        // A broker acknowledgment is not delivery. Retain and republish until the consumer's DB commit.
        db.update("update notification_outbox set attempts=attempts+1,next_attempt=now()+interval '15 seconds',last_error=null where id=? and delivered_at is null",id);
      } catch(Exception error) {
        db.update("update notification_outbox set attempts=attempts+1,next_attempt=now()+least(30,2*power(2,least(attempts,4))) * interval '1 second',last_error=? where id=? and delivered_at is null",
          error.getClass().getSimpleName(),id);
      }
    }
  }
  @KafkaListener(topics="claims.notifications.v1",groupId="claims-notifications")
  void consume(ConsumerRecord<String,String> record,Acknowledgment acknowledgment) throws Exception {
    UUID id=UUID.fromString(json.readTree(record.value()).path("id").asText());
    tx.executeWithoutResult(t -> {
      // Only trusted persisted events may enter the projection; duplicate Kafka records are harmless.
      var rows=db.queryForList("select * from notification_outbox where id=?",id);
      if(rows.isEmpty()) return;
      var event=rows.getFirst();
      db.update("insert into dashboard_notifications(id,claim_id,type,destination_type,destination_id,occurred_at) values (?,?,?,?,?,?) on conflict(id) do nothing",
        id,event.get("claim_id"),event.get("type"),event.get("destination_type"),event.get("destination_id"),event.get("occurred_at"));
      db.update("update notification_outbox set delivered_at=coalesce(delivered_at,now()),last_error=null where id=?",id);
    });
    acknowledgment.acknowledge();
  }
}
