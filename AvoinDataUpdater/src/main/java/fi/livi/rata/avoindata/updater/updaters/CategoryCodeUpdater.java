package fi.livi.rata.avoindata.updater.updaters;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import fi.livi.rata.avoindata.common.domain.cause.CategoryCode;
import fi.livi.rata.avoindata.common.domain.cause.DetailedCategoryCode;
import fi.livi.rata.avoindata.common.domain.cause.ThirdCategoryCode;
import fi.livi.rata.avoindata.updater.service.CategoryCodeService;

@Service
public class CategoryCodeUpdater extends AEntityUpdater<CategoryCode[]> {
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private CategoryCodeService categoryCodeService;

    @Value("${updater.reason.syykoodisto-api-path}")
    private String syykoodiApiPath;

    //Every midnight 1:11
    @Override
    @Scheduled(cron = "0 1 11 * * ?")
    protected void update() {
        log.info("Updating CategoryCodes");

        if (Strings.isNullOrEmpty(syykoodiApiPath)) {
            return;
        }


        String reasonCodePath = "/v1/reason-codes/latest";
        String reasonCategoryPath = "/v1/reason-categories/latest";

        ResponseEntity<JsonNode> reasonCategoryEntity = this.restTemplate.getForEntity(syykoodiApiPath + reasonCategoryPath, JsonNode.class);
        ResponseEntity<JsonNode> reasonCodeEntity = this.restTemplate.getForEntity(syykoodiApiPath + reasonCodePath, JsonNode.class);

        CategoryCode[] categoryCodes = this.merge(reasonCategoryEntity.getBody(), reasonCodeEntity.getBody());

        log.info("Found {} categoryCodes", categoryCodes.length);

        this.persist(reasonCategoryPath + reasonCodePath, this.categoryCodeService::update, categoryCodes);
    }

    private CategoryCode[] merge(JsonNode reasonCategoryResult, JsonNode reasonCodeResult) {
        Map<String, CategoryCode> categoryCodes = new HashMap<>();

        for (JsonNode childElement : reasonCategoryResult) {
            CategoryCode categoryCode = parseCategoryCode(childElement);
            categoryCodes.put(categoryCode.oid, categoryCode);
        }

        for (JsonNode childElement : reasonCodeResult) {
            if (childElement.get("visibilityRestricted").asBoolean() == false) {
                DetailedCategoryCode detailedCategoryCode = parseDetailedCategoryCode(childElement);
                CategoryCode categoryCode = categoryCodes.get(childElement.get("reasonCategoryOid").asText());

                detailedCategoryCode.categoryCode = categoryCode;
                categoryCode.detailedCategoryCodes.add(detailedCategoryCode);

                for (JsonNode detailedReasonCodeElement : childElement.get("detailedReasonCodes")) {
                    if (detailedReasonCodeElement.get("visibilityRestricted").asBoolean() == false) {
                        ThirdCategoryCode thirdCategoryCode = parseThirdCategoryCode(detailedReasonCodeElement);

                        thirdCategoryCode.detailedCategoryCode = detailedCategoryCode;
                        detailedCategoryCode.thirdCategoryCodes.add(thirdCategoryCode);
                    }
                }
            }
        }

        return categoryCodes.values().toArray(new CategoryCode[0]);
    }

    private CategoryCode parseCategoryCode(JsonNode categoryCodeElement) {
        CategoryCode categoryCode = new CategoryCode();
        categoryCode.categoryCode = categoryCodeElement.get("code").textValue();
        categoryCode.categoryName = categoryCodeElement.get("name").textValue();
        categoryCode.validFrom = LocalDate.parse(categoryCodeElement.get("validFromDate").textValue());
        categoryCode.validTo = categoryCodeElement.get("validUntilDate").isNull() ? null : LocalDate.parse(categoryCodeElement.get("validUntilDate").textValue());
        categoryCode.oid = categoryCodeElement.get("oid").asText();
        return categoryCode;
    }

    private ThirdCategoryCode parseThirdCategoryCode(JsonNode thirdCategoryElement) {
        ThirdCategoryCode thirdCategoryCode = new ThirdCategoryCode();
        thirdCategoryCode.thirdCategoryCode = thirdCategoryElement.get("code").textValue();
        thirdCategoryCode.thirdCategoryName = thirdCategoryElement.get("name").textValue();
        thirdCategoryCode.validFrom = LocalDate.parse(thirdCategoryElement.get("validFromDate").textValue());
        thirdCategoryCode.validTo = thirdCategoryElement.get("validUntilDate").isNull() ? null : LocalDate.parse(thirdCategoryElement.get("validUntilDate").textValue());
        thirdCategoryCode.oid = thirdCategoryElement.get("oid").asText();
        return thirdCategoryCode;
    }

    private DetailedCategoryCode parseDetailedCategoryCode(JsonNode detailedCategoryElement) {
        DetailedCategoryCode detailedCategoryCode = new DetailedCategoryCode();
        detailedCategoryCode.detailedCategoryCode = detailedCategoryElement.get("code").textValue();
        detailedCategoryCode.detailedCategoryName = detailedCategoryElement.get("name").textValue();
        detailedCategoryCode.validFrom = LocalDate.parse(detailedCategoryElement.get("validFromDate").textValue());
        detailedCategoryCode.validTo = detailedCategoryElement.get("validUntilDate").isNull() ? null : LocalDate.parse(detailedCategoryElement.get("validUntilDate").textValue());
        detailedCategoryCode.oid = detailedCategoryElement.get("oid").asText();
        return detailedCategoryCode;
    }
}
