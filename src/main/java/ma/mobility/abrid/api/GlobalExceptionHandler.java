package ma.mobility.abrid.api;

import ma.mobility.abrid.core.search.AmbiguousStationException;
import ma.mobility.abrid.core.search.NoDataException;
import ma.mobility.abrid.core.search.StationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;

/**
 * Traduction des exceptions métier en réponses HTTP.
 * Utilise RFC 9457 ProblemDetail (disponible nativement depuis Spring 6).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(StationNotFoundException.class)
    public ProblemDetail handleNotFound(StationNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoDataException.class)
    public ProblemDetail handleNoData(NoDataException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(AmbiguousStationException.class)
    public ProblemDetail handleAmbiguous(AmbiguousStationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler({DateTimeParseException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleBadDate(Exception ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
            "Format de date invalide, utiliser YYYY-MM-DD.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY,
            "Paramètre manquant : " + ex.getParameterName());
    }

    private ProblemDetail problem(HttpStatus status, String detail) {
        var pd = ProblemDetail.forStatus(status);
        pd.setDetail(detail);
        return pd;
    }
}
