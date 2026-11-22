package dev.codeduel.judge;

import dev.codeduel.shared.Protocol.Grade;
import dev.codeduel.shared.Protocol.Job;
import dev.codeduel.shared.Protocol.Status;

import java.util.Map;

final class Grader {
    private final Map<String, Suite> suites = Suite.load();
    private final DockerSandbox sandbox;

    Grader(DockerSandbox sandbox) {
        this.sandbox = sandbox;
    }

    Grade grade(Job job) throws InterruptedException {
        Suite suite = suites.get(job.challengeId());
        int passed = 0;
        int total = suite.publicCases().size();
        for (int index = 0; index < total; index++) {
            Suite.Case test = suite.publicCases().get(index);
            var result = sandbox.run(job.source(), CaseProgram.render(suite, test));
            Status status = status(suite, test, result);
            if (status != Status.PASS) {
                String detail = status == Status.COMPILE_ERROR ? result.output() : description(status);
                return new Grade(status, passed, total, "Caso público " + (index + 1) + ". " + detail.strip());
            }
            passed++;
        }

        if (job.submission()) {
            for (Suite.Case test : suite.hiddenCases()) {
                var result = sandbox.run(job.source(), CaseProgram.render(suite, test));
                Status status = status(suite, test, result);
                if (status != Status.PASS) {
                    return new Grade(status, passed, total, status == Status.UNAVAILABLE
                            ? description(status) : "Un caso oculto no superó la evaluación. " + description(status));
                }
            }
        }
        return new Grade(Status.PASS, passed, total, job.submission()
                ? "Superaste todos los casos públicos y ocultos." : "Superaste todos los casos públicos.");
    }

    private static Status status(Suite suite, Suite.Case test, DockerSandbox.Result result) {
        if (result.status() != Status.PASS) return result.status();
        return CaseProgram.expected(suite, test).equals(result.output()) ? Status.PASS : Status.WRONG_ANSWER;
    }

    private static String description(Status status) {
        return switch (status) {
            case WRONG_ANSWER -> "El resultado es incorrecto.";
            case RUNTIME_ERROR -> "La ejecución terminó con un error.";
            case TIME_LIMIT -> "Se agotó el límite de tiempo o memoria.";
            case UNAVAILABLE -> "Evaluador no disponible. Vuelve a intentarlo.";
            case COMPILE_ERROR -> "No se pudo compilar la solución.";
            case PASS -> "";
        };
    }
}
