#include <cstdlib>
#include <new>
#include "SharedExternal.h"

struct Counts {
    int destroyed = 0;
    int freed = 0;
};

static void freeAfterDestruction(void* user, void* memory) {
    auto& counts = *static_cast<Counts*>(user);
    if (counts.destroyed != 1 || counts.freed != 0)
        std::abort();
    ++counts.freed;
    std::free(memory);
}

static nri::AllocationCallbacks callbacks(Counts& counts) {
    nri::AllocationCallbacks result{};
    result.Free = freeAfterDestruction;
    result.userArg = &counts;
    return result;
}

struct CallbackOwner {
    nri::AllocationCallbacks allocation;
    Counts& counts;

    ~CallbackOwner() {
        ++counts.destroyed;
        allocation = {};
    }
};

struct Device final : nri::DeviceBase {
    nri::DeviceDesc description{};

    explicit Device(Counts& counts) : nri::DeviceBase({}, callbacks(counts)) {}
    const nri::DeviceDesc& GetDesc() const override { return description; }
    void Destruct() override {}
};

struct Resource {
    Device* device;
    Counts& counts;

    Device& GetDevice() const { return *device; }
    ~Resource() {
        ++counts.destroyed;
        device = nullptr;
    }
};

int main() {
    Counts ownedCounts;
    auto* owner = new (std::malloc(sizeof(CallbackOwner))) CallbackOwner{callbacks(ownedCounts), ownedCounts};
    nri::Destroy(owner->allocation, owner);
    if (ownedCounts.freed != 1)
        return 1;

    Counts resourceCounts;
    Device device(resourceCounts);
    auto* resource = new (std::malloc(sizeof(Resource))) Resource{&device, resourceCounts};
    nri::Destroy(resource);
    return resourceCounts.freed == 1 ? 0 : 1;
}
