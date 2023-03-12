package es.udc.eventrider.rest.model.repository;

import es.udc.eventrider.rest.model.domain.Event;
import es.udc.eventrider.rest.model.repository.util.GenericDaoJpa;
import org.springframework.stereotype.Repository;

import javax.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
public class EventDaoJpa extends GenericDaoJpa implements EventDao {

  @Override
  public List<Event> findAll(Map<String, String> query) {
    Boolean hasFilter = !query.isEmpty();

    String queryStr = "select e from Event e";

    if (hasFilter) {
      queryStr += " where ";
      if (hasFilter) {
        for (String key : query.keySet()) {
          String value = query.get(key);

          if(Objects.equals(key, "title")) {
            queryStr += "lower(e." + key + ") like lower('%" + value + "%') and ";
          }

          if(Objects.equals(key, "date")) {
            queryStr += "cast(e.startingDate as date) <= '" + value
              + "' and cast(e.endingDate as date) >= '" + value + "' and ";
          }

          if(Objects.equals(key, "category")) {
            queryStr += "e." + key + " = " + value + " and ";
          }
        }
        // delete the last " AND "
        queryStr = queryStr.substring(0, queryStr.length() - 5);
      }
    }

    TypedQuery<Event> myQuery = entityManager.createQuery(queryStr, Event.class);

    return myQuery.getResultList();
  }

  @Override
  public Event findById(Long id) {
    return entityManager.find(Event.class, id);
  }

  @Override
  public void create(Event event) {
    entityManager.persist(event);
  }

  @Override
  public void update(Event event) {
    entityManager.merge(event);
  }

  @Override
  public void deleteById(Long id) {
    Event event = findById(id);
    delete(event);
  }

  public void delete(Event event) {
    entityManager.remove(event);
  }
}
