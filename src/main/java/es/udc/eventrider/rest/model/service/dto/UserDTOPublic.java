package es.udc.eventrider.rest.model.service.dto;

import es.udc.eventrider.rest.model.domain.Event;
import es.udc.eventrider.rest.model.domain.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class UserDTOPublic {
  private Long id;
  private String name;
  private String surname;
  private String email;
  private String image;
  private Integer upcomingEvents;
  private Integer totalEvents;
  private boolean active = true;

  public UserDTOPublic() {
  }

  public UserDTOPublic(User user) {
    this.id = user.getId();
    this.name = user.getName();
    this.surname = user.getSurname();
    this.email = user.getEmail();
    this.image = user.getImagePath();
    this.setActive(user.isActive());
    this.upcomingEvents = user.getEvents()
      .stream().filter(event -> event.getEventStatus() == Event.EventStatus.PUBLISHED &&
        !event.getStartingDate().isBefore(LocalDateTime.now()))
      .collect(Collectors.toList()).size(); //TODO: INTENTAR DEVOLVER A LISTA
    this.totalEvents = user.getEvents()
      .stream().filter(event -> event.getEventStatus() == Event.EventStatus.PUBLISHED)
      .collect(Collectors.toList()).size();
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getSurname() {
    return surname;
  }

  public void setSurname(String surname) {
    this.surname = surname;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getImage() {
    return image;
  }

  public void setImage(String image) {
    this.image = image;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Integer getUpcomingEvents() {
    return upcomingEvents;
  }

  public void setUpcomingEvents(Integer upcomingEvents) {
    this.upcomingEvents = upcomingEvents;
  }

  public Integer getTotalEvents() {
    return totalEvents;
  }

  public void setTotalEvents(Integer totalEvents) {
    this.totalEvents = totalEvents;
  }
}
