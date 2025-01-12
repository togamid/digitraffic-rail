package fi.livi.rata.avoindata.updater.service.gtfs.realtime;

import com.google.transit.realtime.GtfsRealtime;
import fi.livi.rata.avoindata.common.dao.gtfs.GTFSTripRepository;
import fi.livi.rata.avoindata.common.domain.gtfs.GTFSTimeTableRow;
import fi.livi.rata.avoindata.common.domain.gtfs.GTFSTrain;
import fi.livi.rata.avoindata.common.domain.gtfs.GTFSTrip;
import fi.livi.rata.avoindata.common.domain.trainlocation.TrainLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static fi.livi.rata.avoindata.updater.service.gtfs.GTFSService.FIRST_STOP_SEQUENCE;
import static fi.livi.rata.avoindata.updater.service.gtfs.GTFSTripService.TRIP_REPLACEMENT;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

@Service
public class FeedMessageService {
    private static final Logger log = LoggerFactory.getLogger(FeedMessageService.class);
    private static final int PAST_LIMIT_MINUTES = 30;

    private final GTFSTripRepository gtfsTripRepository;

    public FeedMessageService(final GTFSTripRepository gtfsTripRepository) {
        this.gtfsTripRepository = gtfsTripRepository;
    }

    private static GtfsRealtime.FeedMessage.Builder createBuilderWithHeader() {
        return GtfsRealtime.FeedMessage.newBuilder()
                .setHeader(GtfsRealtime.FeedHeader.newBuilder()
                        .setGtfsRealtimeVersion("2.0")
                        .setTimestamp(new Date().getTime() / 1000)
                        .build());
    }

    public GtfsRealtime.FeedMessage createVehicleLocationFeedMessage(final List<TrainLocation> locations) {
        final TripFinder tripFinder = new TripFinder(gtfsTripRepository.findAll());

        return createBuilderWithHeader()
                .addAllEntity(createVLEntities(tripFinder, locations))
                .build();
    }

    private static String createVesselLocationId(final TrainLocation location) {
        return String.format("%d_location_%d", location.trainLocationId.trainNumber, location.id);
    }

    private static String createCancellationId(final GTFSTrain train) {
        return String.format("%d_cancel_%s", train.id.trainNumber, train.id.departureDate.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    private static String createTripUpdateId(final GTFSTrain train) {
        return String.format("%d_update_%d", train.id.trainNumber, train.version);
    }

    private List<GtfsRealtime.FeedEntity> createVLEntities(final TripFinder tripFinder, final List<TrainLocation> locations) {
        return locations.stream().map(location -> createVLEntity(tripFinder, location))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private GtfsRealtime.FeedEntity createVLEntity(final TripFinder tripFinder, final TrainLocation location) {
        final GTFSTrip trip = tripFinder.find(location);

        return trip == null ? null : GtfsRealtime.FeedEntity.newBuilder()
                .setId(createVesselLocationId(location))
                .setVehicle(GtfsRealtime.VehiclePosition.newBuilder()
                        .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                                .setRouteId(trip.routeId)
                                .setTripId(trip.tripId)
                                .setStartDate(location.trainLocationId.departureDate.format(DateTimeFormatter.BASIC_ISO_DATE))
                                .build())
                        .setPosition(GtfsRealtime.Position.newBuilder()
                                .setLatitude((float)location.location.getY())
                                .setLongitude((float)location.location.getX())
                                .setSpeed(location.speed)
                                .build())
                        .build())
                .build();
    }

    private GtfsRealtime.FeedEntity createTUCancelledEntity(final GTFSTrip trip, final GTFSTrain train) {
        final GtfsRealtime.TripUpdate tripUpdate = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setRouteId(trip.routeId)
                        .setTripId(trip.tripId)
                        .setStartDate(train.id.departureDate.format(DateTimeFormatter.BASIC_ISO_DATE))
                        .setScheduleRelationship(GtfsRealtime.TripDescriptor.ScheduleRelationship.CANCELED)
                        .build())
                .build();

        return GtfsRealtime.FeedEntity.newBuilder()
                .setId(createCancellationId(train))
                .setTripUpdate(tripUpdate)
                .build();
    }

    private boolean isInThePast(final GTFSTimeTableRow arrival, final GTFSTimeTableRow departure) {
        final ZonedDateTime limit = ZonedDateTime.now().minusMinutes(PAST_LIMIT_MINUTES);

        final boolean isArrivalInPast = arrival == null || isBefore(arrival, limit);
        final boolean isDepartureInPast = departure == null || isBefore(departure, limit);

        return isArrivalInPast && isDepartureInPast;
    }

    private boolean isBefore(final GTFSTimeTableRow row, final ZonedDateTime limit) {
        // both scheduled time and live-estimate must be in the past to be skipped
        return row.liveEstimateTime != null && row.liveEstimateTime.isBefore(limit) && row.scheduledTime.isBefore(limit);
    }

    private GtfsRealtime.TripUpdate.StopTimeUpdate createStopTimeUpdate(final int stopSequence, final GTFSTimeTableRow arrival, final GTFSTimeTableRow departure) {
        // it's in the past(PAST_LIMIT_MINUTES), don't report it!
        if(isInThePast(arrival, departure)) {
            return null;
        }

        final boolean arrivalHasTime = arrival != null && arrival.hasEstimateOrActualTime();
        final boolean departureHasTime = departure != null && departure.hasEstimateOrActualTime();

        // if there's no estimates yet, do not report
        if(!arrivalHasTime && !departureHasTime) {
            return null;
        }

        final String stopId = arrival == null ? departure.stationShortCode : arrival.stationShortCode;
        final GtfsRealtime.TripUpdate.StopTimeUpdate.Builder builder = GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                .setStopId(stopId)
                .setStopSequence(stopSequence);

        // GTFS delay is seconds, our difference is minutes
        if(arrivalHasTime) {
            builder.setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                    .setDelay(arrival.delayInSeconds())
                    .build());
        }
        if(departureHasTime) {
            // sometimes departure has live estimate that is before arrival's scheduled time(and departure has no estimate or actual time)
            // in that case, fake a delay for arrival that's equals to departure's delay
            if(arrival != null && !arrivalHasTime && arrival.scheduledTime.isAfter(departure.getActualOrEstimate())) {
                builder.setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setDelay(departure.delayInSeconds())
                        .build());
            }

            builder.setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                    .setDelay(departure.delayInSeconds())
                    .build());
        }

        return builder.build();
    }

    private boolean delaysDiffer(final GtfsRealtime.TripUpdate.StopTimeUpdate previous, @Nonnull final GtfsRealtime.TripUpdate.StopTimeUpdate current) {
        if (previous == null) {
            return true;
        }

        final int previousDelay = previous.getDeparture().getDelay();

        if (current.hasArrival() && current.getArrival().getDelay() != previousDelay) {
            return true;
        }

        return current.hasDeparture() && current.getDeparture().getDelay() != previousDelay;
    }

    private List<GtfsRealtime.TripUpdate.StopTimeUpdate> createStopTimeUpdates(final GTFSTrain train) {
        final List<GtfsRealtime.TripUpdate.StopTimeUpdate> updates = new ArrayList<>();
        int stopSequence = FIRST_STOP_SEQUENCE;

        // this is then previous stop that was added to updates-list
        GtfsRealtime.TripUpdate.StopTimeUpdate previous = createStopTimeUpdate(stopSequence++, null, train.timeTableRows.get(0));
        if(previous != null) {
            // if first stop and delay is negative, then
            // we generate a new stop with fabricated arrival that has the same delay as the departure
            // this is done because stop_times.txt has arrival and departure times for each stop, even for the first and last
            if(previous.getDeparture().getDelay() < 0) {
                final GtfsRealtime.TripUpdate.StopTimeUpdate.Builder builder = GtfsRealtime.TripUpdate.StopTimeUpdate.newBuilder()
                        .setStopId(previous.getStopId())
                        .setStopSequence(previous.getStopSequence());

                builder.setArrival(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setDelay(previous.getDeparture().getDelay())
                        .build());

                builder.setDeparture(GtfsRealtime.TripUpdate.StopTimeEvent.newBuilder()
                        .setDelay(previous.getDeparture().getDelay())
                        .build());

                previous = builder.build();
            }

            updates.add(previous);
        }

        for(int i = 1; i < train.timeTableRows.size();) {
            final GTFSTimeTableRow arrival = train.timeTableRows.get(i++);
            final GTFSTimeTableRow departure = train.timeTableRows.size() == i ? null : train.timeTableRows.get(i++);

            // skip stations where train does not stop
            if(includeStop(arrival, departure)) {
                final GtfsRealtime.TripUpdate.StopTimeUpdate current = createStopTimeUpdate(stopSequence++, arrival, departure);

                if (current != null && delaysDiffer(previous, current)) {
                    updates.add(current);

                    previous = current;
                }
            }
        }

        return updates;
    }

    private boolean includeStop(final GTFSTimeTableRow arrival, final GTFSTimeTableRow departure) {

        // include only stops where scheduled times are different and stop is commercial
        return departure == null || (!arrival.scheduledTime.equals(departure.scheduledTime) &&
                (isTrue(arrival.commercialStop) || isTrue(departure.commercialStop)));
    }

    private GtfsRealtime.FeedEntity createTUUpdateEntity(final GTFSTrip trip, final GTFSTrain train) {
        final List<GtfsRealtime.TripUpdate.StopTimeUpdate> stopTimeUpdates = createStopTimeUpdates(train);

        if(stopTimeUpdates.isEmpty()) {
            return null;
        }

        final GtfsRealtime.TripUpdate tripUpdate = GtfsRealtime.TripUpdate.newBuilder()
                .setTrip(GtfsRealtime.TripDescriptor.newBuilder()
                        .setRouteId(trip.routeId)
                        .setTripId(trip.tripId)
                        .setStartDate(train.id.departureDate.format(DateTimeFormatter.BASIC_ISO_DATE))
                        .build())
                .addAllStopTimeUpdate(stopTimeUpdates)
                .build();

        return GtfsRealtime.FeedEntity.newBuilder()
                .setId(createTripUpdateId(train))
                .setTripUpdate(tripUpdate)
                .build();
    }

    public GtfsRealtime.FeedEntity createTUEntity(final TripFinder tripFinder, final GTFSTrain train) {
        try {
            final GTFSTrip trip = tripFinder.find(train);

            if (trip != null) {
                if (train.cancelled) {
                    return createTUCancelledEntity(trip, train);
                }

                if (trip.version != train.version) {
                    return createTUUpdateEntity(trip, train);
                }
            }
        } catch (final Exception e) {
            log.error("exception when creating entity " + train.id, e);
        }

        // new train, we do nothing.  the realtime-spec does not support creation of new trips!
        return null;
    }

    public List<GtfsRealtime.FeedEntity> createTUEntities(final TripFinder tripFinder, final List<GTFSTrain> trains) {
        return trains.stream()
                .map(train -> createTUEntity(tripFinder, train))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public GtfsRealtime.FeedMessage createTripUpdateFeedMessage(final List<GTFSTrain> trains) {
        log.info("creating TripUpdateFeedMessages for {} trains", trains.size());

        final TripFinder tripFinder = new TripFinder(gtfsTripRepository.findAll());

        log.info("creating TripUpdateFeedMessages for {} train numbers", tripFinder.tripMap.entrySet().size());

        final GtfsRealtime.FeedMessage message = createBuilderWithHeader()
                .addAllEntity(createTUEntities(tripFinder, trains))
                .build();

        log.info("created TripUpdateFeedMessages for {} entities", message.getEntityCount());

        return message;
    }

    static class TripFinder {
        private final Map<Long, List<GTFSTrip>> tripMap = new HashMap<>();

        TripFinder(final List<GTFSTrip> trips) {
            trips.forEach(t -> {
                tripMap.putIfAbsent(t.id.trainNumber, new ArrayList<>());
                tripMap.get(t.id.trainNumber).add(t);
            });
        }

        Stream<GTFSTrip> safeStream(final List<GTFSTrip> trips) {
            return trips == null ? Stream.empty() : trips.stream();
        }

        GTFSTrip find(final GTFSTrain train) {
            final List<GTFSTrip> trips = tripMap.get(train.id.trainNumber);

            return findTripFromList(trips, train.id.trainNumber, train.id.departureDate);
        }

        GTFSTrip find(final TrainLocation location) {
            final List<GTFSTrip> trips = tripMap.get(location.trainLocationId.trainNumber);

            return findTripFromList(trips, location.trainLocationId.trainNumber, location.trainLocationId.departureDate);
        }

        GTFSTrip findTripFromList(final List<GTFSTrip> trips, final Long trainNumber, final LocalDate departureDate) {
            final List<GTFSTrip> filtered = safeStream(trips)
                    .filter(t -> t.id.trainNumber.equals(trainNumber))
                    .filter(t -> !t.id.startDate.isAfter(departureDate))
                    .filter(t -> !t.id.endDate.isBefore(departureDate))
                    .collect(Collectors.toList());

            if(filtered.isEmpty()) {
                log.trace("Could not find trip for train number " + trainNumber);
                return null;
            }

            if(filtered.size() > 1) {
                final Optional<GTFSTrip> replacement = findReplacement(filtered);

                if(replacement.isEmpty()) {
                    log.info("Multiple trips:" + filtered);
                    log.error("Could not find replacement from multiple " + trainNumber);
                }

                return replacement.orElse(null);
            }

            return filtered.get(0);
        }

        Optional<GTFSTrip> findReplacement(final List<GTFSTrip> trips) {
            return trips.stream()
                    .filter(trip -> trip.tripId.endsWith(TRIP_REPLACEMENT))
                    .findFirst();
        }
    }
}
