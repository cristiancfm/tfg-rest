package es.udc.eventrider.rest.model.service;

import es.udc.eventrider.rest.config.Properties;
import es.udc.eventrider.rest.model.domain.Event;
import es.udc.eventrider.rest.model.domain.User;
import es.udc.eventrider.rest.model.exception.ModelException;
import es.udc.eventrider.rest.model.exception.NotFoundException;
import es.udc.eventrider.rest.model.exception.OperationNotAllowed;
import es.udc.eventrider.rest.model.repository.EventCategoryDao;
import es.udc.eventrider.rest.model.repository.EventDao;
import es.udc.eventrider.rest.model.repository.UserDao;
import es.udc.eventrider.rest.model.service.dto.EventDTO;
import es.udc.eventrider.rest.model.service.dto.EventDTOCreate;
import es.udc.eventrider.rest.model.service.dto.EventDTOEdit;
import es.udc.eventrider.rest.model.service.dto.ImageDTO;
import es.udc.eventrider.rest.model.service.util.EmailServiceImpl;
import es.udc.eventrider.rest.model.service.util.ImageService;
import es.udc.eventrider.rest.security.SecurityUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.management.InstanceNotFoundException;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional(readOnly = true, rollbackFor = Exception.class)
public class EventService {
  @Autowired
  private EventDao eventDAO;

  @Autowired
  private UserDao userDAO;

  @Autowired
  private EventCategoryDao eventCategoryDao;

  @Autowired
  private ImageService imageService;

  @Autowired
  private EmailServiceImpl emailService;

  @Autowired
  private UserService userService;

  @Autowired
  Properties properties;

  private Path rootLoc;

  public List<EventDTO> findAll(Map<String, String> query) {
    Stream<Event> events = eventDAO.findAll(query).stream();
    if(!SecurityUtils.getCurrentUserIsAdmin()) {
      events = events.filter(e -> e.getHost().isActive());
    }
    return events.map(event -> new EventDTO(event)).collect(Collectors.toList());
  }

  public EventDTO findById(Long id) throws NotFoundException {
    Event event = eventDAO.findById(id);
    if(event == null || !SecurityUtils.getCurrentUserIsAdmin() && !event.getHost().isActive()){
      throw new NotFoundException(id.toString(), Event.class);
    }
    return new EventDTO(event);
  }

  @Transactional(readOnly = false, rollbackFor = Exception.class)
  public void saveEventImageById(Long id, MultipartFile file) throws InstanceNotFoundException, ModelException {
    Event event = eventDAO.findById(id);
    if (event == null)
      throw new NotFoundException(id.toString(), Event.class);

    String filePath = imageService.saveImage(ImageService.Entity.EVENT, file, event.getId());

    List<String> imagePaths = event.getImagePaths();
    if(event.getImagePaths() == null){
      imagePaths = new ArrayList<>();
    }
    imagePaths.add(filePath);
    event.setImagePaths(imagePaths);

    eventDAO.update(event);
  }

  public ImageDTO getEventImageById(Long id, Long imgId) throws InstanceNotFoundException, ModelException {
    Event event = eventDAO.findById(id);
    if(event == null || !SecurityUtils.getCurrentUserIsAdmin() && !event.getHost().isActive()) {
      throw new NotFoundException(id.toString(), Event.class);
    }
    if(event.getImagePaths() == null) {
      return null;
    }

    return imageService.getImage(ImageService.Entity.EVENT, event.getImagePath(imgId), event.getId());
  }

  @Transactional(readOnly = false, rollbackFor = Exception.class)
  public void deleteEventImageById(Long id, Long imgId) throws InstanceNotFoundException, ModelException {
    Event event = eventDAO.findById(id);
    if (event == null)
      throw new NotFoundException(id.toString(), Event.class);

    imageService.deleteImage(ImageService.Entity.EVENT, event.getImagePath(imgId), event.getId());

    Path folderPath = getRootLoc().resolve(  "events/" + id + "/" + "images");
    File[] files = folderPath.toFile().listFiles();
    List<String> imagePaths = new ArrayList<>();
    for (int i = 0; i < files.length; i++) {
      File file = files[i];
      imagePaths.add(file.getName());
    }
    event.setImagePaths(imagePaths);

    eventDAO.update(event);
  }

  private Path getRootLoc() {
    if (rootLoc == null)
      this.rootLoc = Paths.get(properties.getImagesPath());
    return rootLoc;
  }

  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = false, rollbackFor = Exception.class)
  public EventDTO create(EventDTOCreate event) throws OperationNotAllowed {
    Event dbEvent = new Event();

    dbEvent.setTitle(event.getTitle());

    Long userId = userService.getCurrentUserWithAuthority().getId();
    dbEvent.setHost(userDAO.findById(userId));

    //Check if starting & ending dates are not past dates
    if(event.getStartingDate().isBefore(LocalDateTime.now()) ||
      event.getEndingDate().isBefore(LocalDateTime.now())){
      throw new OperationNotAllowed("The starting, ending or both dates are past dates");
    }
    //Check if ending date is after starting date
    if(event.getEndingDate().isBefore(event.getStartingDate())){
      throw new OperationNotAllowed("The ending date is before the starting date");
    }
    dbEvent.setStartingDate(event.getStartingDate());
    dbEvent.setEndingDate(event.getEndingDate());

    GeometryFactory geometryFactory = new GeometryFactory();
    Point point = geometryFactory.createPoint(new Coordinate(event.getCoordinateX(), event.getCoordinateY()));
    dbEvent.setPoint(point);

    dbEvent.setLocationDetails(event.getLocationDetails());
    dbEvent.setDescription(event.getDescription());

    dbEvent.setStatus(Event.EventStatus.UNREVIEWED); //TODO verified user (no hace falta revisar los eventos)

    if(event.getExistingCategoryChecked()){
      dbEvent.setCategory(eventCategoryDao.findById(Long.parseLong(event.getExistingCategoryId())));
    }
    //TODO create category

    //Send email to user followers using parallel threads
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    for (User user : dbEvent.getHost().getFollowers()) {
      StringBuilder emailText = new StringBuilder("The user " + dbEvent.getHost().getName() + " " +
        dbEvent.getHost().getSurname() + " created a new event:\n");
      emailText.append("Title: ").append(dbEvent.getTitle());

      executorService.execute(() -> {
        emailService.sendSimpleMessage(
          "cristian.ferreiro@udc.es", // TODO cambiar a user.getEmail()
          "Event Rider: " + dbEvent.getHost().getName() + " " + dbEvent.getHost().getSurname()
            + " created a new event",
          emailText.toString());
      });
    }
    executorService.shutdown();

    //Send email to category subscribers using parallel threads
    executorService = Executors.newFixedThreadPool(10);
    for (User user : dbEvent.getCategory().getSubscribers()) {
      StringBuilder emailText = new StringBuilder("The category " + dbEvent.getCategory().getName() +
        " was updated with a new event:\n");
      emailText.append("Title: ").append(dbEvent.getTitle());

      executorService.execute(() -> {
        emailService.sendSimpleMessage(
          "cristian.ferreiro@udc.es", // TODO cambiar a user.getEmail()
          "Event Rider: The category " + dbEvent.getCategory().getName() +
            " was updated",
          emailText.toString());
      });
    }
    executorService.shutdown();


    eventDAO.create(dbEvent);
    return new EventDTO(dbEvent);
  }

  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = false, rollbackFor = Exception.class)
  public EventDTO update(EventDTOEdit event) throws NotFoundException {
    Event dbEvent = eventDAO.findById(event.getId());
    if (dbEvent == null) {
      throw new NotFoundException(event.getId().toString(), Event.class);
    }

    Map<String, String> updatedFields = new HashMap<>();

    if(!Objects.equals(event.getTitle(), dbEvent.getTitle())){
      dbEvent.setTitle(event.getTitle());
      updatedFields.put("Title: ", dbEvent.getTitle());
    }

    if(event.getSubscribers() != null){
      dbEvent.getSubscribers().clear();
      event.getSubscribers().forEach(s -> {
        dbEvent.getSubscribers().add(userDAO.findById(s.getId()));
      });
    }

    if(event.getSaves() != null){
      dbEvent.getSaves().clear();
      event.getSaves().forEach(s -> {
        dbEvent.getSaves().add(userDAO.findById(s.getId()));
      });
    }

    if(event.getStartingDate().compareTo(dbEvent.getStartingDate()) != 0){
      dbEvent.setStartingDate(event.getStartingDate());
      updatedFields.put("Starting Date: ",
        dbEvent.getStartingDate().toLocalDate().toString() + " " +
        dbEvent.getStartingDate().toLocalTime().toString());
    }

    if(event.getEndingDate().compareTo(dbEvent.getEndingDate()) != 0){
      dbEvent.setEndingDate(event.getEndingDate());
      updatedFields.put("Ending Date: ",
        dbEvent.getEndingDate().toLocalDate().toString() + " " +
        dbEvent.getEndingDate().toLocalTime().toString());
    }

    if(event.getCoordinateX() != dbEvent.getPoint().getX() ||
      event.getCoordinateY() != dbEvent.getPoint().getY()){
      GeometryFactory geometryFactory = new GeometryFactory();
      Point point = geometryFactory.createPoint(new Coordinate(event.getCoordinateX(), event.getCoordinateY()));
      dbEvent.setPoint(point);
      updatedFields.put("Coordinates: ", dbEvent.getPoint().getX() + ", " + dbEvent.getPoint().getY());
    }

    if(!Objects.equals(event.getLocationDetails(), dbEvent.getLocationDetails())){
      dbEvent.setLocationDetails(event.getLocationDetails());
      updatedFields.put("Location Details: ", dbEvent.getLocationDetails());
    }

    if(!Objects.equals(event.getDescription(), dbEvent.getDescription())){
      dbEvent.setDescription(event.getDescription());
      updatedFields.put("Description: ", dbEvent.getDescription());
    }

    if(event.getExistingCategoryChecked() != null){
      if(event.getExistingCategoryChecked()){
        dbEvent.setCategory(eventCategoryDao.findById(Long.parseLong(event.getExistingCategoryId())));
      }
    } //TODO create category

    if(!Objects.equals(event.getCancellationReason(), dbEvent.getCancellationReason())){
      dbEvent.setCancellationReason(event.getCancellationReason());
    }

    if(!Objects.equals(event.getAdminComments(), dbEvent.getAdminComments())){
      dbEvent.setAdminComments(event.getAdminComments());
    }

    if(!Objects.equals(event.getStatus(), dbEvent.getStatus())){
      dbEvent.setStatus(event.getStatus());

      if(dbEvent.getStatus() == Event.EventStatus.CANCELLED){
        //TODO email notification to event subscribers
      }

      if(dbEvent.getStatus() == Event.EventStatus.PUBLISHED){
        //TODO email notification to event host
      }

      if(dbEvent.getStatus() == Event.EventStatus.REJECTED){
        //TODO email notification to event host
      }
    }

    //Send email to event subscribers using parallel threads
    if(!updatedFields.isEmpty()){
      ExecutorService executorService = Executors.newFixedThreadPool(10);
      for (User user: dbEvent.getSubscribers()) {
        StringBuilder emailText = new StringBuilder("The event " + dbEvent.getTitle() +
          " was updated with new information:\n");
        for (Map.Entry<String, String> entry : updatedFields.entrySet()) {
          emailText.append(entry.getKey()).append(entry.getValue()).append("\n");
        }
        executorService.execute(() -> {
          emailService.sendSimpleMessage(
            "cristian.ferreiro@udc.es", //TODO cambiar a user.getEmail()
            "Event Rider: " + dbEvent.getTitle() + " was updated",
            emailText.toString());
        });
      }
      executorService.shutdown();
    }

    eventDAO.update(dbEvent);
    return new EventDTO(dbEvent);
  }

  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = false, rollbackFor = Exception.class)
  public void delete(Long id) {
    eventDAO.deleteById(id);
  }

  @Scheduled(cron = "0 0 0 * * *") // Daily midnight execution
  //@EventListener(ApplicationReadyEvent.class)
  public void sendEmailsWithUpcomingEvents(){
    //Send email with events happening tomorrow to event subscribers using parallel threads
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    List<User> dbUsers = userDAO.findAll();
    for (User dbUser : dbUsers) {
      boolean tomorrowEvents = false;
      StringBuilder emailText = new StringBuilder("The following events are happening tomorrow:\n\n");
      for (Event subscribedEvent : dbUser.getSubscribedEvents()){
        if(subscribedEvent.getStartingDate().toLocalDate().isEqual(
          LocalDateTime.now().toLocalDate().plusDays(1))) {
          //startingDate is tomorrow
          tomorrowEvents = true;
          emailText.append("Title: ").append(subscribedEvent.getTitle()).append("\n");
          emailText.append("Starting Date: ").append(subscribedEvent.getStartingDate().toLocalDate().toString())
            .append(" ").append(subscribedEvent.getStartingDate().toLocalTime().toString()).append("\n");
          emailText.append("Ending Date: ").append(subscribedEvent.getEndingDate().toLocalDate().toString())
            .append(" ").append(subscribedEvent.getEndingDate().toLocalTime().toString()).append("\n\n");
        }
      }
      if(tomorrowEvents){
        executorService.execute(() -> {
          emailService.sendSimpleMessage(
            "cristian.ferreiro@udc.es", //TODO cambiar a user.getEmail()
            "Event Rider: Subscribed events about to happen",
            emailText.toString());
        });
      }
    }
    executorService.shutdown();
  }
}
