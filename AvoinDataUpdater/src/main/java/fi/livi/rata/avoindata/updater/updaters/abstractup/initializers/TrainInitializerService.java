package fi.livi.rata.avoindata.updater.updaters.abstractup.initializers;


import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import fi.livi.rata.avoindata.common.dao.train.ForecastRepository;
import fi.livi.rata.avoindata.common.domain.common.TrainId;
import fi.livi.rata.avoindata.common.domain.jsonview.TrainJsonView;
import fi.livi.rata.avoindata.common.domain.train.Forecast;
import fi.livi.rata.avoindata.common.domain.train.TimeTableRow;
import fi.livi.rata.avoindata.common.domain.train.Train;
import fi.livi.rata.avoindata.common.utils.BatchExecutionService;
import fi.livi.rata.avoindata.updater.service.MQTTPublishService;
import fi.livi.rata.avoindata.updater.service.TrainLockExecutor;
import fi.livi.rata.avoindata.updater.service.miku.ForecastMergingService;
import fi.livi.rata.avoindata.updater.updaters.abstractup.AbstractPersistService;
import fi.livi.rata.avoindata.updater.updaters.abstractup.persist.TrainPersistService;

@Service
public class TrainInitializerService extends AbstractDatabaseInitializer<Train> {
    private Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private TrainPersistService trainPersistService;

    @Autowired
    private ForecastMergingService forecastMergingService;

    @Autowired
    private ForecastRepository forecastRepository;

    @Autowired
    private BatchExecutionService bes;

    @Autowired
    private TrainLockExecutor trainLockExecutor;

    @Autowired
    private MQTTPublishService mqttPublishService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getPrefix() {
        return "trains";
    }

    @Override
    public AbstractPersistService<Train> getPersistService() {
        return trainPersistService;
    }

    @Override
    protected Class<Train[]> getEntityCollectionClass() {
        return Train[].class;
    }

    @Override
    protected List<Train> doUpdate() {
        return trainLockExecutor.executeInLock(() -> {
            List<Train> updatedTrains = super.doUpdate();
            log.info("Train base update complete. Starting mqtt sending");
            try {
                for (Train train : updatedTrains) {
                    log.info("Pushing train to mqtt {}", train);
                    String trainAsString = objectMapper.writerWithView(TrainJsonView.LiveTrains.class).writeValueAsString(train);

                    mqttPublishService.publishString(
                            String.format("trains/%s/%s/%s/%s/%s/%s/%s/%s", train.id.departureDate, train.id.trainNumber,
                                    train.trainCategory, train.trainType, train.operator.operatorShortCode, train.commuterLineID,
                                    train.runningCurrently, train.timetableType), trainAsString);

                    Set<String> announcedStations = new HashSet<>();
                    for (TimeTableRow timeTableRow : train.timeTableRows) {
                        String stationShortCode = timeTableRow.station.stationShortCode;
                        if (!announcedStations.contains(stationShortCode)) {
                            announcedStations.add(stationShortCode);
                            mqttPublishService.publishString(String.format("trains-by-station/%s", stationShortCode), trainAsString);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error publishing trains to MQTT", e);
            }

            log.info("Ended update complete");

            return updatedTrains;
        });
    }

    @Override
    public List<Train> modifyEntitiesBeforePersist(final List<Train> entities) {
        final List<TrainId> trainIds = Lists.newArrayList(Iterables.transform(entities, f -> f.id));
        if (!trainIds.isEmpty()) {

            List<Forecast> forecasts = bes.transform(trainIds, t -> forecastRepository.findByTrains(t));

            final ImmutableListMultimap<Train, Forecast> trainMap = Multimaps.index(forecasts, forecast -> forecast.timeTableRow.train);

            for (final Train train : entities) {
                final ImmutableList<Forecast> trainsForecasts = trainMap.get(train);
                if (!trainsForecasts.isEmpty()) {
                    forecastMergingService.mergeEstimates(train, trainsForecasts);
                }
            }
        }

        return entities;
    }
}
