package fi.livi.rata.avoindata.LiikeInterface.domain.entities.routeset;

import fi.livi.rata.avoindata.LiikeInterface.domain.BaseEntity;
import org.hibernate.annotations.Type;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Set;

@Entity
public class Routeset extends BaseEntity {
    public static final String KEY_NAME = "ROSE_ID";

    @Id
    @Column(name = KEY_NAME)
    public Long id;

    @Version
    @Column(name = "ORA_ROWSCN")
    public Long version;

    @Type(type="org.hibernate.type.LocalDateType")
    public LocalDate departureDate;

    @Type(type="org.hibernate.type.ZonedDateTimeType")
    public ZonedDateTime messageTime;

    public String routeType;
    public String clientSystem;
    public String trainNumber;

    @OneToMany(mappedBy = "routeset")
    public Set<Routesection> routesections;
}
