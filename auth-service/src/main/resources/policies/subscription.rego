package auth.subscription

import time

# Subscription plan definitions
free_plan = {
    "name": "free",
    "features": {
        "chatbot": {
            "text": true,
            "voice": true
        },
        "android_app": false,
        "valid_days": 4
    }
}

bronze_plan = {
    "name": "bronze",
    "features": {
        "chatbot": {
            "text": true,
            "voice": false
        },
        "android_app": false,
        "valid_days": 30
    }
}

silver_plan = {
    "name": "silver",
    "features": {
        "chatbot": {
            "text": true,
            "voice": true
        },
        "android_app": false,
        "valid_days": 30
    }
}

gold_plan = {
    "name": "gold",
    "features": {
        "chatbot": {
            "text": true,
            "voice": true
        },
        "android_app": true,
        "valid_days": 30
    }
}

# Get plan by name
get_plan[name] = plan {
    name := input.plan_name
    plan := {
        "free": free_plan,
        "bronze": bronze_plan,
        "silver": silver_plan,
        "gold": gold_plan
    }[name]
}

# Check if subscription is valid
subscription_valid = valid {
    plan := get_plan[input.subscription.plan_name]
    start_time := input.subscription.start_time
    valid_until := time.add_seconds(start_time, (plan.features.valid_days * 24 * 60 * 60))
    now := time.now()
    valid := now < valid_until
}

# Check feature access
has_feature[input.user_id, feature] = result {
    subscription := input.subscriptions[input.user_id]
    plan := get_plan[subscription.plan_name]
    feature_path := split(feature, ".")
    feature_value := get_feature_value(plan.features, feature_path)
    result := feature_value == true
}

get_feature_value(obj, []) = obj
get_feature_value(obj, [k | rest]) = result {
    v := obj[k]
    result := get_feature_value(v, rest)
}
