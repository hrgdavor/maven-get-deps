package hr.hrg.maven.getdeps;

import java.util.*;
import java.util.function.*;

/**
 * Utility class to provide semantic versions of common Stream API operations
 * to improve readability.
 */
public class StreamUtil {

    private StreamUtil() {} // Utility class

    /**
     * Maps each element of a collection to a new value.
     *
     * @param <T>        the type of elements in the source collection
     * @param <R>        the type of elements in the result list
     * @param collection the source collection, may be null
     * @param mapper     the function to apply to each element
     * @return a new list containing the mapped elements, or an empty list if collection is null
     */
    public static <T, R> List<R> map(Collection<T> collection, Function<T, R> mapper) {
        if (collection == null) return Collections.emptyList();
        List<R> result = new ArrayList<>(collection.size());
        for (T item : collection) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    /**
     * Maps each element of an array to a new value.
     *
     * @param <T>    the type of elements in the source array
     * @param <R>    the type of elements in the result list
     * @param array  the source array, may be null
     * @param mapper the function to apply to each element
     * @return a new list containing the mapped elements, or an empty list if array is null
     */
    public static <T, R> List<R> map(T[] array, Function<T, R> mapper) {
        if (array == null) return Collections.emptyList();
        List<R> result = new ArrayList<>(array.length);
        for (T item : array) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    /**
     * Filters elements of a collection based on a predicate.
     *
     * @param <T>        the type of elements in the collection
     * @param collection the source collection, may be null
     * @param predicate  the condition to check for each element
     * @return a new list containing elements that match the predicate, or an empty list if collection is null
     */
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

    /**
     * Checks if any element in the collection matches the given predicate.
     *
     * @param <T>        the type of elements in the collection
     * @param collection the source collection, may be null
     * @param predicate  the condition to check
     * @return true if at least one element matches the predicate, false otherwise or if collection is null
     */
    public static <T> boolean any(Collection<T> collection, Predicate<T> predicate) {
        if (collection == null) return false;
        for (T item : collection) {
            if (predicate.test(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts the number of elements in the collection that match the given predicate.
     *
     * @param <T>        the type of elements in the collection
     * @param collection the source collection, may be null
     * @param predicate  the condition to check
     * @return the number of matching elements, or 0 if collection is null
     */
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

    /**
     * Maps each element of a collection to a collection of values and flattens the result into a single list.
     *
     * @param <T>        the type of elements in the source collection
     * @param <R>        the type of elements in the result list
     * @param collection the source collection, may be null
     * @param mapper     the function that produces a collection of values for each input element
     * @return a flattened list of all mapped values, or an empty list if collection is null
     */
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

    /**
     * Maps each element of a collection and returns the results as a sorted list.
     *
     * @param <T>        the type of elements in the source collection
     * @param <R>        the type of elements in the result list
     * @param collection the source collection, may be null
     * @param mapper     the function to apply to each element
     * @param comparator the comparator to use for sorting the results
     * @return a new sorted list of mapped values
     */
    public static <T, R> List<R> mapToSorted(Collection<T> collection, Function<T, R> mapper, Comparator<R> comparator) {
        List<R> result = map(collection, mapper);
        Collections.sort(result, comparator);
        return result;
    }

    /**
     * Splits a string by a regex, trims each part, and collects non-empty parts into a set.
     *
     * @param str   the string to split, may be null or blank
     * @param regex the regex to split by
     * @return a set of trimmed, non-empty parts, or an empty set if str is null or blank
     */
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
