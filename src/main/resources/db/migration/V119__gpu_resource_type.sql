-- A new enum value must commit before subsequent migrations use it.
alter type resource_type add value if not exists 'GPU';
