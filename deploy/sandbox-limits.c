#define _GNU_SOURCE
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sched.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#if defined(__x86_64__)
#define NATIVE_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define NATIVE_ARCH AUDIT_ARCH_AARCH64
#else
#error Unsupported sandbox architecture
#endif

#define DENY(syscall, error) \
    BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (syscall), 0, 1), \
    BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (error))

static int fail(const char *operation) {
    perror(operation);
    return 14;
}

static int limit(int resource, rlim_t value) {
    struct rlimit setting = {.rlim_cur = value, .rlim_max = value};
    return setrlimit(resource, &setting);
}

static int pin_to_one_cpu(void) {
    cpu_set_t available;
    cpu_set_t selected;
    if (sched_getaffinity(0, sizeof(available), &available) != 0) return -1;
    unsigned int draw = 0;
    (void) getrandom(&draw, sizeof(draw), GRND_NONBLOCK);
    int cpu_count = CPU_COUNT(&available);
    if (cpu_count == 0) {
        errno = EINVAL;
        return -1;
    }
    int remaining = (int) (draw % (unsigned int) cpu_count);
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (CPU_ISSET(cpu, &available) && remaining-- == 0) {
            CPU_ZERO(&selected);
            CPU_SET(cpu, &selected);
            return sched_setaffinity(0, sizeof(selected), &selected);
        }
    }
    errno = EINVAL;
    return -1;
}

static int restrict_system_calls(void) {
    struct sock_filter code[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NATIVE_ARCH, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
#endif
#ifdef __NR_fork
        DENY(__NR_fork, EPERM),
#endif
#ifdef __NR_vfork
        DENY(__NR_vfork, EPERM),
#endif
        DENY(__NR_clone3, ENOSYS),
        DENY(__NR_sched_setaffinity, EPERM),
        DENY(__NR_unshare, EPERM),
        DENY(__NR_setns, EPERM),
        DENY(__NR_io_uring_setup, EPERM),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_clone, 0, 3),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
        BPF_JUMP(BPF_JMP | BPF_JSET | BPF_K, CLONE_THREAD, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog program = {.len = sizeof(code) / sizeof(code[0]), .filter = code};
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return -1;
    return prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &program);
}

int main(int argc, char **argv) {
    if (argc < 2) return 14;
    if (limit(RLIMIT_AS, 640L * 1024 * 1024) != 0
            || limit(RLIMIT_CPU, 5) != 0 || limit(RLIMIT_NPROC, 128) != 0) return fail("setrlimit");
    if (pin_to_one_cpu() != 0) return fail("sched_setaffinity");
    if (restrict_system_calls() != 0) return fail("seccomp");
    execvp(argv[1], argv + 1);
    return fail("execvp");
}
