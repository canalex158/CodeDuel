package dev.codeduel.judge;

import dev.codeduel.shared.Protocol.Job;
import dev.codeduel.shared.Protocol.Status;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "CODEDUEL_DOCKER_TESTS", matches = "1")
class JudgeDockerTest {
    private static final DockerSandbox SANDBOX = new DockerSandbox("codeduel-sandbox:1.0.0",
            System.getenv().getOrDefault("CODEDUEL_SANDBOX_RUNTIME", ""), Duration.ofSeconds(8));
    private static final Grader GRADER = new Grader(SANDBOX);

    private static final Map<String, String> REFERENCES = Map.ofEntries(
            Map.entry("sum", "int solve(int[] a) { return Arrays.stream(a).sum(); }"),
            Map.entry("maximum", "int solve(int[] a) { return Arrays.stream(a).max().orElseThrow(); }"),
            Map.entry("reverse", "String solve(String s) { return new StringBuilder(s).reverse().toString(); }"),
            Map.entry("evens", "int solve(int[] a) { return (int) Arrays.stream(a).filter(n -> n % 2 == 0).count(); }"),
            Map.entry("palindrome", "boolean solve(String s) { return s.contentEquals(new StringBuilder(s).reverse()); }"),
            Map.entry("max-subarray", """
                    int solve(int[] a) {
                        int best = a[0], sum = a[0];
                        for (int i = 1; i < a.length; i++) {
                            sum = Math.max(a[i], sum + a[i]); best = Math.max(best, sum);
                        }
                        return best;
                    }
                    """),
            Map.entry("brackets", """
                    boolean solve(String s) {
                        Deque<Character> stack = new ArrayDeque<>();
                        for (char c : s.toCharArray()) {
                            int index = "([{".indexOf(c);
                            if (index >= 0) stack.push(")]}".charAt(index));
                            else if (stack.isEmpty() || stack.pop() != c) return false;
                        }
                        return stack.isEmpty();
                    }
                    """),
            Map.entry("move-zeroes", """
                    int[] solve(int[] a) {
                        int[] result = new int[a.length];
                        int i = 0;
                        for (int n : a) if (n != 0) result[i++] = n;
                        return result;
                    }
                    """),
            Map.entry("subarray-count", """
                    int solve(int[] a, int target) {
                        Map<Long,Integer> frequencies = new HashMap<>();
                        frequencies.put(0L, 1);
                        long prefix = 0;
                        int result = 0;
                        for (int n : a) {
                            prefix += n;
                            result += frequencies.getOrDefault(prefix - target, 0);
                            frequencies.merge(prefix, 1, Integer::sum);
                        }
                        return result;
                    }
                    """),
            Map.entry("longest-distinct", """
                    int solve(String s) {
                        int[] last = new int[128];
                        int left = 0, best = 0;
                        for (int i = 0; i < s.length(); i++) {
                            char c = s.charAt(i);
                            left = Math.max(left, last[c]);
                            last[c] = i + 1;
                            best = Math.max(best, i - left + 1);
                        }
                        return best;
                    }
                    """),
            Map.entry("histogram", """
                    long solve(int[] a) {
                        Deque<Integer> stack = new ArrayDeque<>();
                        long best = 0;
                        for (int i = 0; i <= a.length; i++) {
                            int height = i == a.length ? 0 : a[i];
                            while (!stack.isEmpty() && a[stack.peek()] > height) {
                                int popped = a[stack.pop()];
                                int left = stack.isEmpty() ? -1 : stack.peek();
                                best = Math.max(best, (long) popped * (i - left - 1));
                            }
                            stack.push(i);
                        }
                        return best;
                    }
                    """),
            Map.entry("lis", """
                    int solve(int[] a) {
                        int[] tails = new int[a.length];
                        int n = 0;
                        for (int value : a) {
                            int index = Arrays.binarySearch(tails, 0, n, value);
                            if (index < 0) index = -index - 1;
                            tails[index] = value;
                            if (index == n) n++;
                        }
                        return n;
                    }
                    """),
            Map.entry("rain", """
                    long solve(int[] a) {
                        if (a.length == 0) return 0;
                        int[] right = new int[a.length];
                        right[a.length - 1] = a[a.length - 1];
                        for (int i = a.length - 2; i >= 0; i--) right[i] = Math.max(a[i], right[i + 1]);
                        int left = 0;
                        long water = 0;
                        for (int i = 0; i < a.length; i++) {
                            left = Math.max(left, a[i]);
                            water += Math.min(left, right[i]) - a[i];
                        }
                        return water;
                    }
                    """),
            Map.entry("window", """
                    String solve(String s, String target) {
                        if (target.isEmpty()) return "";
                        int[] counts = new int[128];
                        for (char c : target.toCharArray()) counts[c]++;
                        int remaining = target.length(), left = 0, start = 0, size = s.length() + 1;
                        for (int right = 0; right < s.length(); right++) {
                            if (counts[s.charAt(right)]-- > 0) remaining--;
                            while (remaining == 0) {
                                if (right - left + 1 < size) { size = right - left + 1; start = left; }
                                if (++counts[s.charAt(left++)] > 0) remaining++;
                            }
                        }
                        return size > s.length() ? "" : s.substring(start, start + size);
                    }
                    """),
            Map.entry("grid", """
                    int solve(int[][] grid) {
                        int h = grid.length, w = grid[0].length;
                        if (grid[0][0] == 1 || grid[h - 1][w - 1] == 1) return -1;
                        var queue = new ArrayDeque<int[]>();
                        queue.add(new int[]{0, 0, 0});
                        grid[0][0] = 1;
                        int[][] next = {{1,0}, {-1,0}, {0,1}, {0,-1}};
                        while (!queue.isEmpty()) {
                            int[] node = queue.remove();
                            if (node[0] == h - 1 && node[1] == w - 1) return node[2];
                            for (int[] d : next) {
                                int y = node[0] + d[0], x = node[1] + d[1];
                                if (y >= 0 && y < h && x >= 0 && x < w && grid[y][x] == 0) {
                                    grid[y][x] = 1;
                                    queue.add(new int[]{y, x, node[2] + 1});
                                }
                            }
                        }
                        return -1;
                    }
                    """));

    @TestFactory Stream<DynamicTest> allChallengeFixturesPassIndependentSolutions() {
        return REFERENCES.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(reference ->
                DynamicTest.dynamicTest(reference.getKey(), () -> {
                    var grade = GRADER.grade(new Job(reference.getKey(), solution(reference.getValue()), true));
                    assertEquals(Status.PASS, grade.status(), grade.detail());
                }));
    }

    @Test void runtimeFailureAndRunawayProgramsAreRejected() throws Exception {
        var runtime = GRADER.grade(new Job("sum",
                solution("int solve(int[] a) { throw new IllegalStateException(); }"), false));
        assertEquals(Status.RUNTIME_ERROR, runtime.status(), runtime.detail());
        var timeout = GRADER.grade(new Job("sum", solution("int solve(int[] a) { for (;;) {} }"), false));
        assertEquals(Status.TIME_LIMIT, timeout.status(), timeout.detail());
    }

    @Test void guestCannotStartAnotherProcess() throws Exception {
        var grade = GRADER.grade(new Job("sum", solution("""
                int solve(int[] a) {
                    try {
                        new ProcessBuilder("/bin/true").start();
                        return Integer.MIN_VALUE;
                    } catch (java.io.IOException limited) {
                        return Arrays.stream(a).sum();
                    }
                }
                """), false));
        assertEquals(Status.PASS, grade.status(), grade.detail());
    }

    @Test void incorrectMethodSignatureIsRejected() throws Exception {
        var grade = GRADER.grade(new Job("sum", solution("long solve(int[] a) { return 6; }"), false));
        assertEquals(Status.COMPILE_ERROR, grade.status(), grade.detail());
        assertTrue(grade.detail().contains("firma exacta"));
    }

    private static String solution(String method) {
        return "import java.util.*;\npublic class Solution {\npublic static " + method + "\n}";
    }
}
