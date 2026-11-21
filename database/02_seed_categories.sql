USE smart_support;
SET SESSION time_zone = '+00:00';

INSERT INTO categories (code, name, description, triage_keywords, resolution_playbook) VALUES
('UNCATEGORIZED', 'Needs triage', 'No clear category or manual review pending.',
 JSON_ARRAY(), 'Clarify the affected service and desired outcome, then identify the appropriate support team.'),
('BILLING', 'Billing', 'Charges, invoices, payment failures and refund requests.',
 JSON_ARRAY('charged twice', 'invoice', 'refund', 'payment'),
 'Verify the transaction in the billing console. Confirm any duplicate and follow the approved refund policy.'),
('ACCOUNT', 'Account access', 'Sign-in, account recovery and permissions.',
 JSON_ARRAY('sign in', 'login', 'locked out', 'password reset'),
 'Verify account ownership through the approved flow. Guide the customer through the recovery process.'),
('TECHNICAL', 'Technical issue', 'Errors, service interruptions and broken features.',
 JSON_ARRAY('crash', 'error', 'outage', 'unavailable'),
 'Collect the error, time and affected feature. Check service health and escalate reproducible issues.'),
('DELIVERY', 'Delivery', 'Missing, delayed or incorrect deliveries.',
 JSON_ARRAY('shipment', 'tracking', 'delivery', 'package'),
 'Verify order and carrier status in the fulfillment system, then explain the next available step.'),
('PRODUCT', 'Product feedback', 'Feature requests, usability feedback and general product comments.',
 JSON_ARRAY('feature request', 'suggestion', 'feature idea'),
 'Capture the use case and expected outcome, then route the feedback to Product.')
ON DUPLICATE KEY UPDATE code = categories.code;