package hr.hrg.maven.getdeps;

import java.util.*;
import java.util.function.*;

/**
 * Utility class to provide semantic versions of common Stream API operations
 * to improve readability.
 */
public class StreamUtil {

    private StreamUtil() {} // Utility class

    public static <T, R> List<R> map(Collection<T> collection, Function<T, R> mapper) {
        if (collection == null) return Collections.emptyList();
        List<R> result = new ArrayList<>(collection.size());
        for (T item : collection) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    public static <T, R> List<R> map(T[] array, Function<T, R> mapper) {
        if (array == null) return Collections.emptyList();
        List<R> result = new ArrayList<>(array.length);
        for (T item : array) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    public static <T> List<T> filter(Collection<T> collection, Predicate<T> predicate) {
        if (collection == null) return Collections.emptyList();
        List<T> result = new ArrayList<>();
        for (T item : collection) {
            if (predicate.test(item)) {
                result.add(item);
            }
        }
        return result;
    }

    public static <T> boolean any(Collection<T> collection, Predicate<T> predicate) {
        if (collection == null) return false;
        for (T item : collection) {
            if (predicate.test(item)) {
                return true;
            }
        }
        return false;
    }

    public static <T> long count(Collection<T> collection, Predicate<T> predicate) {
        if (collection == null) return 0;
        long count = 0;
        for (T item : collection) {
            if (predicate.test(item)) {
                count++;
            }
        }
        return count;
    }

    public static <T, R> List<R> flatMap(Collection<T> collection, Function<T, Collection<? extends R>> mapper) {
        if (collection == null) return Collections.emptyList();
        List<R> result = new ArrayList<>();
        for (T item : collection) {
            Collection<? extends R> mapped = mapper.apply(item);
            if (mapped != null) {
                result.addAll(mapped);
            }
        }
        return result;
    }

    public static <T, R> List<R> mapToSorted(Collection<T> collection, Function<T, R> mapper, Comparator<R> comparator) {
        List<R> result = map(collection, mapper);
        Collections.sort(result, comparator);
        return result;
    }

    public static Set<String> splitToSet(String str, String regex) {
        if (str == null || str.isBlank()) return Collections.emptySet();
        String[] parts = str.split(regex);
        Set<String> result = new HashSet<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
