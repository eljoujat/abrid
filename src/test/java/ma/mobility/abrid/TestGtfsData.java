package ma.mobility.abrid;

import java.io.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Données GTFS minimales partagées par tous les tests. */
public final class TestGtfsData {

    public static final String STOPS = """
        stop_id,stop_name,stop_lat,stop_lon
        TANGER,Tanger,35.769,-5.800
        CASA_VOYAGEURS,Casa-Voyageurs,33.590,-7.620
        FES,Fès,34.036,-5.000
        MARRAKECH,Marrakech,31.630,-8.000
        """;
    public static final String ROUTES = """
        route_id,route_short_name,route_long_name
        LIGNE_NORD,L1,Tanger - Casa
        LIGNE_SUD,L2,Casa - Marrakech
        LIGNE_EST,L3,Casa - Fes
        """;
    public static final String CALENDAR = """
        service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
        SVC_TOUS,1,1,1,1,1,1,1,20240101,20251231
        """;
    public static final String TRIPS = """
        trip_id,route_id,service_id,trip_headsign
        T001,LIGNE_NORD,SVC_TOUS,Casa-Voyageurs
        T002,LIGNE_SUD,SVC_TOUS,Marrakech
        T003,LIGNE_EST,SVC_TOUS,Fès
        """;
    public static final String STOP_TIMES = """
        trip_id,stop_id,stop_sequence,arrival_time,departure_time
        T001,TANGER,1,08:00:00,08:00:00
        T001,CASA_VOYAGEURS,2,12:00:00,12:00:00
        T002,CASA_VOYAGEURS,1,13:00:00,13:00:00
        T002,MARRAKECH,2,17:00:00,17:00:00
        T003,CASA_VOYAGEURS,1,14:00:00,14:00:00
        T003,FES,2,18:00:00,18:00:00
        """;

    private TestGtfsData() {}

    public static byte[] buildZip() throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            addEntry(zos, "stops.txt",         STOPS);
            addEntry(zos, "routes.txt",        ROUTES);
            addEntry(zos, "trips.txt",         TRIPS);
            addEntry(zos, "stop_times.txt",    STOP_TIMES);
            addEntry(zos, "calendar.txt",      CALENDAR);
            addEntry(zos, "calendar_dates.txt","service_id,date,exception_type\n");
        }
        return baos.toByteArray();
    }

    private static void addEntry(ZipOutputStream zos, String name, String content)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes());
        zos.closeEntry();
    }
}
