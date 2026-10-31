package dev.rohit.labelaudit;

import java.util.ArrayDeque;

/** Small in-memory screen history. Returning to an existing destination unwinds child screens. */
public final class Navigation<T> {
  public static final class Entry<T> {
    public final String route;
    public final T state;

    Entry(String r, T s) {
      route = r;
      state = s;
    }
  }

  private final ArrayDeque<Entry<T>> entries = new ArrayDeque<>();

  public void show(String current, String next, T state) {
    if (next.equals("Today")) {
      entries.clear();
      return;
    }
    if (current == null || current.equals(next)) return;
    boolean returning = false;
    for (Entry<T> e : entries) if (e.route.equals(next)) returning = true;
    if (returning) {
      while (!entries.pop().route.equals(next)) {}
    } else entries.push(new Entry<>(current, state));
  }

  public java.util.ArrayList<String> routes() {
    java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for (Entry<T> e : entries) out.add(e.route);
    java.util.Collections.reverse(out);
    return out;
  }

  public void restore(java.util.List<String> routes, java.util.function.Function<String, T> state) {
    entries.clear();
    for (String route : routes) entries.push(new Entry<>(route, state.apply(route)));
  }

  public Entry<T> back() {
    return entries.poll();
  }

  public boolean canGoBack() {
    return !entries.isEmpty();
  }
}
